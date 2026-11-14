package dev.plexusmodloader.compat.forge.fml;

import dev.plexusmodloader.compat.forge.ForgeEventPairs;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraftforge.eventbus.api.bus.CancellableEventBus;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.util.thread.EffectiveSide;

/**
 * Forge on NeoForge, F-N3 (ADR-0051): listens on NeoForge's game bus for each event of {@link
 * ForgeEventPairs#GAME_EVENTS} and posts Forge's event of the pair to Forge's own bus for it (the event class's static
 * {@code BUS}, in Forge's {@code universal} jar among the loader libraries).
 *
 * <p>The classes of both events are found by name in the game class loader and used through {@link
 * MethodHandles#publicLookup()}: NeoForge's events are in FML's game layer, which this library module does not read,
 * and public lookup needs no read edge for public members of exported packages.
 *
 * <p>Thread: {@link #install} on FML's construct stage; the listeners run on whichever thread NeoForge posts on.
 */
final class ForgeGameEvents {

    private static final MethodHandle TIME_LEFT;
    private static final MethodHandle SIDE;

    static {
        try {
            MethodHandles.Lookup own = MethodHandles.lookup();
            TIME_LEFT = own.findStatic(
                    ForgeGameEvents.class,
                    "timeLeft",
                    MethodType.methodType(BooleanSupplier.class, MethodHandle.class, Object.class));
            SIDE = own.findStatic(
                    ForgeGameEvents.class,
                    "side",
                    MethodType.methodType(net.minecraftforge.fml.LogicalSide.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static boolean installed;

    private ForgeGameEvents() {}

    /**
     * Adds the listeners once, to NeoForge's {@code NeoForge.EVENT_BUS}.
     *
     * @throws IllegalStateException when an event or member of a pair is not in this NeoForge or Forge release
     */
    static synchronized void install(ClassLoader game, Consumer<String> log) {
        if (installed) {
            return;
        }
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        IEventBus neoForge;
        try {
            Class<?> holder = Class.forName("net.neoforged.neoforge.common.NeoForge", true, game);
            neoForge = (IEventBus) lookup.findStaticGetter(holder, "EVENT_BUS", IEventBus.class)
                    .invoke();
        } catch (Throwable e) {
            throw new IllegalStateException(
                    "NeoForge.EVENT_BUS could not be read, so Forge mods get no game events" + ": " + e, e);
        }
        List<Forwarding> forwardings = new ArrayList<>();
        for (ForgeEventPairs.Pair pair : ForgeEventPairs.GAME_EVENTS) {
            forwardings.add(Forwarding.of(pair, game, lookup));
        }
        forwardings.forEach(forwarding -> forwarding.listen(neoForge));
        installed = true;
        log.accept("Forge on NeoForge: "
                + forwardings.size()
                + " NeoForge game event(s) passed on to Forge mods (experimental, F-N3)");
    }

    /** A NeoForge event and how its Forge event is built and posted. */
    private record Forwarding(
            Class<? extends Event> neoForge, EventBus<?> bus, MethodHandle factory, boolean cancellable) {

        static Forwarding of(ForgeEventPairs.Pair pair, ClassLoader game, MethodHandles.Lookup lookup) {
            try {
                Class<? extends Event> neoForge =
                        Class.forName(pair.neoForge(), false, game).asSubclass(Event.class);
                Class<?> forge = Class.forName(pair.forge(), true, game);
                List<Class<?>> types = new ArrayList<>();
                List<MethodHandle> values = new ArrayList<>();
                for (ForgeEventPairs.Argument argument : pair.arguments()) {
                    switch (argument) {
                        case ForgeEventPairs.Getter getter -> {
                            Method method = neoForge.getMethod(getter.method());
                            types.add(method.getReturnType());
                            values.add(lookup.unreflect(method)
                                    .asType(MethodType.methodType(method.getReturnType(), Object.class)));
                        }
                        case ForgeEventPairs.TimeLeft timeLeft -> {
                            Method method = neoForge.getMethod(timeLeft.method());
                            if (method.getReturnType() != boolean.class) {
                                throw new NoSuchMethodException(pair.neoForge() + "." + timeLeft.method() + "()Z");
                            }
                            MethodHandle hasTime =
                                    lookup.unreflect(method).asType(MethodType.methodType(boolean.class, Object.class));
                            types.add(BooleanSupplier.class);
                            values.add(MethodHandles.insertArguments(TIME_LEFT, 0, hasTime));
                        }
                        case ForgeEventPairs.Side side -> {
                            types.add(net.minecraftforge.fml.LogicalSide.class);
                            values.add(SIDE);
                        }
                    }
                }
                MethodHandle constructor = lookup.findConstructor(forge, MethodType.methodType(void.class, types));
                MethodHandle factory = MethodHandles.permuteArguments(
                                MethodHandles.filterArguments(constructor, 0, values.toArray(MethodHandle[]::new)),
                                MethodType.methodType(forge, Object.class),
                                new int[types.size()])
                        .asType(MethodType.methodType(Object.class, Object.class));
                EventBus<?> bus = (EventBus<?>) forge.getField("BUS").get(null);
                boolean cancellable = bus instanceof CancellableEventBus<?>;
                if (cancellable && !ICancellableEvent.class.isAssignableFrom(neoForge)) {
                    throw new IllegalStateException(
                            pair.forge() + " " + "is cancellable, unlike" + " " + pair.neoForge());
                }
                return new Forwarding(neoForge, bus, factory, cancellable);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException(
                        "Cannot pass on " + pair.neoForge() + " -> " + pair.forge() + ": " + e, e);
            }
        }

        <T extends Event> void listen(IEventBus neoForgeBus) {
            @SuppressWarnings("unchecked")
            Class<T> type = (Class<T>) neoForge;
            neoForgeBus.addListener(EventPriority.NORMAL, false, type, this::forward);
        }

        void forward(Event event) {
            if (!bus.hasListeners()) {
                return;
            }
            Object forgeEvent;
            try {
                forgeEvent = (Object) factory.invokeExact((Object) event);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
            if (post(bus, forgeEvent) && cancellable) {
                ((ICancellableEvent) event).setCanceled(true);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean post(EventBus<?> bus, Object event) {
        return ((EventBus<net.minecraftforge.eventbus.internal.Event>) bus)
                .post((net.minecraftforge.eventbus.internal.Event) event);
    }

    private static BooleanSupplier timeLeft(MethodHandle hasTime, Object event) {
        return () -> {
            try {
                return (boolean) hasTime.invokeExact(event);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static net.minecraftforge.fml.LogicalSide side(Object event) {
        return EffectiveSide.get().isClient()
                ? net.minecraftforge.fml.LogicalSide.CLIENT
                : net.minecraftforge.fml.LogicalSide.SERVER;
    }
}
