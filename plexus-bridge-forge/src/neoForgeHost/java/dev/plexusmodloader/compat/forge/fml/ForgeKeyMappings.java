package dev.plexusmodloader.compat.forge.fml;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.function.Consumer;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

/**
 * Forge on NeoForge, F-N3 (ADR-0051): Forge's {@code RegisterKeyMappingsEvent} from NeoForge's, in a client game. NeoForge
 * posts its event once, to every mod's bus ({@code ModLoader.postEvent} from {@code ClientHooks.onRegisterKeyMappings} in
 * {@code Options.<init>}); Forge posts its own once, to the event's static {@code BUS} ({@code
 * ForgeHooksClient.onRegisterKeyMappings}, from {@code initClientHooks} in {@code Minecraft.<init>}, after the options
 * are made). Both events add a key mapping by appending it to the options' {@code keyMappings} (their {@code register}
 * methods, compared on the 26.2 jars), so Forge's event is built on the same options and posted once, the first time a
 * Forge mod's bus gets NeoForge's event. NeoForge posts it before the options load the saved keys, which Forge's mods
 * then get too.
 *
 * <p>NeoForge's event has no getter for its options: the host reads its private field {@code options} with core
 * reflection (read only). That read worked in the NeoForge 26.2.0.93 client, whose jar has no module descriptor; it has
 * not run in another release's client, and if the field cannot be made accessible there, every Forge mod fails at
 * construction. The pre-launch check checks the field's name and type ({@code ForgeEventPairCheck.missingKeyMappings}).
 *
 * <p>Thread: {@link #listen} on FML's construct stage; the listener runs on the client thread making the options.
 */
final class ForgeKeyMappings {

    static final String NEOFORGE_EVENT = "net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent";
    static final String FORGE_EVENT = "net.minecraftforge.client.event.RegisterKeyMappingsEvent";

    private static ForgeKeyMappings forwarding;
    private static Object lastPosted;

    private final Class<? extends Event> neoForge;
    private final MethodHandle options;
    private final MethodHandle forgeEvent;
    private final EventBus<?> bus;

    private ForgeKeyMappings(ClassLoader game) throws ReflectiveOperationException {
        neoForge = Class.forName(NEOFORGE_EVENT, false, game).asSubclass(Event.class);
        Class<?> forge = Class.forName(FORGE_EVENT, true, game);
        Field field = neoForge.getDeclaredField("options");
        field.setAccessible(true);
        options =
                MethodHandles.lookup().unreflectGetter(field).asType(MethodType.methodType(Object.class, Object.class));
        Constructor<?> constructor = forge.getConstructor(field.getType());
        forgeEvent = MethodHandles.publicLookup()
                .unreflectConstructor(constructor)
                .asType(MethodType.methodType(Object.class, Object.class));
        bus = (EventBus<?>) forge.getField("BUS").get(null);
    }

    /**
     * Adds the listener to a Forge mod's NeoForge mod bus {@code modBus}; the events are found the first time.
     *
     * @throws IllegalStateException when an event or member is not in this NeoForge or Forge release
     */
    static synchronized void listen(IEventBus modBus, ClassLoader game, Consumer<String> log) {
        if (forwarding == null) {
            try {
                forwarding = new ForgeKeyMappings(game);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException(
                        "Cannot pass on " + NEOFORGE_EVENT + " -> " + FORGE_EVENT + ": " + e, e);
            }
            log.accept("Forge on NeoForge: NeoForge's key mapping registration passed on to Forge mods (experimental,"
                    + " F-N3)");
        }
        forwarding.listenOn(modBus);
    }

    @SuppressWarnings("unchecked")
    private void listenOn(IEventBus modBus) {
        modBus.addListener(EventPriority.NORMAL, false, (Class<Event>) neoForge, ForgeKeyMappings::forward);
    }

    private static void forward(Event event) {
        ForgeKeyMappings current;
        synchronized (ForgeKeyMappings.class) {
            if (event == lastPosted) {
                return;
            }
            lastPosted = event;
            current = forwarding;
        }
        try {
            Object forge =
                    (Object) current.forgeEvent.invokeExact((Object) current.options.invokeExact((Object) event));
            ForgeGameEvents.post(current.bus, forge);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
