package dev.plexusmodloader.compat.forge.fml;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;

/**
 * Forge on NeoForge, F-N4 (ADR-0051): registration. For each registry NeoForge fills, its {@code RegisterEvent} reaches
 * every mod container's bus in mod order; a Forge mod's container then posts Forge's {@code RegisterEvent} for the same
 * registry key and registry to the Forge mod's bus group. Forge's {@code DeferredRegister} listens there and adds its
 * entries; Forge's event has no Forge registry here, so its {@code register} adds them to the registry it was given,
 * NeoForge's, with {@code Registry.register} as NeoForge's own event does, and Forge's {@code RegistryObject}s take
 * their values from that registry.
 *
 * <p>NeoForge's event is in FML's game layer and is used through {@link MethodHandles#publicLookup()} (see {@link
 * ForgeGameEvents}). Forge's event constructor is package-private; Forge's {@code universal} jar is an automatic module,
 * whose packages are open, so it is reached by reflection. No class is added to Forge's packages.
 *
 * <p>Thread: {@link #of} on FML's construct stage; {@link #post} on the thread NeoForge sends registry events on.
 */
final class ForgeRegistration {

    private static final String NEOFORGE_EVENT = "net.neoforged.neoforge.registries.RegisterEvent";
    private static final String FORGE_EVENT = "net.minecraftforge.registries.RegisterEvent";

    private static ForgeRegistration instance;

    private final Class<? extends Event> neoForgeEvent;
    private final MethodHandle registryKey;
    private final MethodHandle registry;
    private final Constructor<?> forgeEvent;
    private final MethodHandle forgeBus;

    private ForgeRegistration(
            Class<? extends Event> neoForgeEvent,
            MethodHandle registryKey,
            MethodHandle registry,
            Constructor<?> forgeEvent,
            MethodHandle forgeBus) {
        this.neoForgeEvent = neoForgeEvent;
        this.registryKey = registryKey;
        this.registry = registry;
        this.forgeEvent = forgeEvent;
        this.forgeBus = forgeBus;
    }

    /**
     * The registration of the game whose classes {@code game} loads, resolved once.
     *
     * @throws IllegalStateException when NeoForge's or Forge's registry event is not as Plexus Mod Loader expects
     */
    static synchronized ForgeRegistration of(ClassLoader game) {
        if (instance != null) {
            return instance;
        }
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            Class<? extends Event> neoForge =
                    Class.forName(NEOFORGE_EVENT, false, game).asSubclass(Event.class);
            Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey", false, game);
            Class<?> registry = Class.forName("net.minecraft.core.Registry", false, game);
            Class<?> forgeRegistry = Class.forName("net.minecraftforge.registries.ForgeRegistry", false, game);
            Class<?> forge = Class.forName(FORGE_EVENT, true, game);
            Constructor<?> constructor = forge.getDeclaredConstructor(resourceKey, forgeRegistry, registry);
            constructor.setAccessible(true);
            instance = new ForgeRegistration(
                    neoForge,
                    lookup.findVirtual(neoForge, "getRegistryKey", MethodType.methodType(resourceKey))
                            .asType(MethodType.methodType(Object.class, Object.class)),
                    lookup.findVirtual(neoForge, "getRegistry", MethodType.methodType(registry))
                            .asType(MethodType.methodType(Object.class, Object.class)),
                    constructor,
                    lookup.findStatic(forge, "getBus", MethodType.methodType(EventBus.class, BusGroup.class)));
            return instance;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(
                    "NeoForge's RegisterEvent and Forge's RegisterEvent could not be resolved, so Forge mods get no registry events"
                            + ": " + e,
                    e);
        }
    }

    /** Adds the listener that passes NeoForge's registry events on to {@code forgeModBus}, on the mod's {@code modBus}. */
    <T extends Event> void listen(IEventBus modBus, BusGroup forgeModBus) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) neoForgeEvent;
        modBus.addListener(type, event -> post(event, forgeModBus));
    }

    /** Posts Forge's registry event for NeoForge's {@code event} to {@code forgeModBus}, when it has listeners. */
    void post(Object event, BusGroup forgeModBus) {
        try {
            EventBus<?> bus = (EventBus<?>) forgeBus.invoke(forgeModBus);
            if (!bus.hasListeners()) {
                return;
            }
            Object forge = forgeEvent.newInstance(
                    (Object) registryKey.invokeExact(event), null, (Object) registry.invokeExact(event));
            post(bus, forge);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void post(EventBus<?> bus, Object event) {
        ((EventBus<net.minecraftforge.eventbus.internal.Event>) bus)
                .post((net.minecraftforge.eventbus.internal.Event) event);
    }
}
