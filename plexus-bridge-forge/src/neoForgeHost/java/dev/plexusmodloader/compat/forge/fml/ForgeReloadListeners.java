package dev.plexusmodloader.compat.forge.fml;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

/**
 * Forge on NeoForge, F-N3 (ADR-0051): Forge's {@code AddReloadListenerEvent} from NeoForge's {@code
 * AddServerReloadListenersEvent}. Both are posted from the same lambda of {@code ReloadableServerResources.loadResources}
 * (Forge's {@code ForgeEventFactory.onResourceReload}, NeoForge's {@code EventHooks.onResourceReload}; compared on the
 * 26.2 patched client jars). Forge's event is built from NeoForge's server resources and a registry lookup and posted
 * to its own bus; then every listener the Forge mods added is added to NeoForge's event under a name of its own, so that
 * NeoForge's sort runs it after the vanilla listeners, where Forge appends them.
 *
 * <p>The registry lookup is the server resources' {@code fullRegistries().lookup()}: the registry access NeoForge's own
 * event gives in 26.1 to 26.2 ({@code LayeredRegistryAccess.compositeAccess()}), on every release, since NeoForge 26.3's
 * event has none. Forge passes {@code ReloadableServerRegistries.LoadResult.lookupWithUpdatedTags()} instead: the same
 * registries, but a Forge listener that reads a tag through this lookup sees the tags from before the reload.
 *
 * <p>Forge wraps each listener so that it does nothing while Forge's mod loading has failed ({@code
 * WrappedStateAwareListener}, which reads Forge's {@code ModLoader.loadingStateValid}, never set here because Forge's
 * loading does not run, so the wrapper would skip every reload): the host adds the listener it wraps. That record is
 * private to Forge's event; its accessor {@code wrapped()} is read with core reflection, which Forge's {@code universal}
 * jar allows as an automatic module (every package open). Forge's {@code getConditionContext()} calls a member of
 * Forge's patches; the runtime leaves out the mods that call it.
 *
 * <p>Classes are found by name in the game class loader and used through {@link MethodHandles#publicLookup()}, as in
 * {@link ForgeGameEvents}; the identifier type comes from the parameter of NeoForge's {@code addListener}.
 *
 * <p>Thread: {@link #install} on FML's construct stage; the listener runs on the thread that reloads server resources.
 */
final class ForgeReloadListeners {

    static final String NEOFORGE_EVENT = "net.neoforged.neoforge.event.AddServerReloadListenersEvent";
    static final String FORGE_EVENT = "net.minecraftforge.event.AddReloadListenerEvent";
    static final String FORGE_WRAPPER = FORGE_EVENT + "$WrappedStateAwareListener";
    static final String NAMESPACE = "plexus_forge_on_neoforge";

    private static boolean installed;

    private final MethodHandle serverResources;
    private final MethodHandle registryAccess;
    private final MethodHandle addListener;
    private final MethodHandle identifier;
    private final MethodHandle forgeEvent;
    private final MethodHandle listeners;
    private final Class<?> wrapper;
    private final MethodHandle wrapped;
    private final EventBus<?> bus;
    private final Consumer<String> log;

    private ForgeReloadListeners(ClassLoader game, MethodHandles.Lookup lookup, Consumer<String> log)
            throws ReflectiveOperationException {
        Class<?> neoForge = Class.forName(NEOFORGE_EVENT, false, game);
        Class<?> forge = Class.forName(FORGE_EVENT, true, game);
        Method resources = neoForge.getMethod("getServerResources");
        Method full = resources.getReturnType().getMethod("fullRegistries");
        Method access = full.getReturnType().getMethod("lookup");
        serverResources = lookup.unreflect(resources).asType(MethodType.methodType(Object.class, Object.class));
        registryAccess = MethodHandles.filterReturnValue(lookup.unreflect(full), lookup.unreflect(access))
                .asType(MethodType.methodType(Object.class, Object.class));
        Method add = null;
        for (Method method : neoForge.getMethods()) {
            if (method.getName().equals("addListener") && method.getParameterCount() == 2) {
                add = method;
            }
        }
        if (add == null) {
            throw new NoSuchMethodException(NEOFORGE_EVENT + ".addListener(Identifier, PreparableReloadListener)");
        }
        addListener = lookup.unreflect(add)
                .asType(MethodType.methodType(void.class, Object.class, Object.class, Object.class));
        Class<?> identifierType = add.getParameterTypes()[0];
        identifier = lookup.findStatic(
                        identifierType,
                        "fromNamespaceAndPath",
                        MethodType.methodType(identifierType, String.class, String.class))
                .asType(MethodType.methodType(Object.class, String.class, String.class));
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : forge.getConstructors()) {
            Class<?>[] types = candidate.getParameterTypes();
            if (types.length == 2
                    && types[0].isAssignableFrom(resources.getReturnType())
                    && types[1].isAssignableFrom(access.getReturnType())) {
                constructor = candidate;
            }
        }
        if (constructor == null) {
            throw new NoSuchMethodException(FORGE_EVENT + ".<init>(server resources, registry lookup)");
        }
        forgeEvent = lookup.unreflectConstructor(constructor)
                .asType(MethodType.methodType(Object.class, Object.class, Object.class));
        listeners = lookup.findVirtual(forge, "getListeners", MethodType.methodType(List.class))
                .asType(MethodType.methodType(List.class, Object.class));
        wrapper = Class.forName(FORGE_WRAPPER, true, game);
        Method wrappedMethod = wrapper.getDeclaredMethod("wrapped");
        wrappedMethod.setAccessible(true);
        wrapped = MethodHandles.lookup()
                .unreflect(wrappedMethod)
                .asType(MethodType.methodType(Object.class, Object.class));
        bus = (EventBus<?>) forge.getField("BUS").get(null);
        this.log = log;
    }

    /**
     * Adds the listener once, to NeoForge's {@code NeoForge.EVENT_BUS}.
     *
     * @throws IllegalStateException when an event or member is not in this NeoForge or Forge release
     */
    @SuppressWarnings("unchecked")
    static synchronized void install(ClassLoader game, Consumer<String> log) {
        if (installed) {
            return;
        }
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        IEventBus neoForgeBus;
        ForgeReloadListeners forwarding;
        Class<? extends Event> neoForge;
        try {
            Class<?> holder = Class.forName("net.neoforged.neoforge.common.NeoForge", true, game);
            neoForgeBus = (IEventBus) lookup.findStaticGetter(holder, "EVENT_BUS", IEventBus.class)
                    .invoke();
            neoForge = (Class<? extends Event>)
                    Class.forName(NEOFORGE_EVENT, false, game).asSubclass(Event.class);
            forwarding = new ForgeReloadListeners(game, lookup, log);
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot pass on " + NEOFORGE_EVENT + " -> " + FORGE_EVENT + ": " + e, e);
        }
        neoForgeBus.addListener(EventPriority.NORMAL, false, (Class<Event>) neoForge, forwarding::forward);
        installed = true;
        log.accept("Forge on NeoForge: NeoForge's server reload listener event passed on to Forge mods (experimental,"
                + " F-N3)");
    }

    private void forward(Event event) {
        if (!bus.hasListeners()) {
            return;
        }
        try {
            Object resources = (Object) serverResources.invokeExact((Object) event);
            Object access = (Object) registryAccess.invokeExact(resources);
            Object forge = (Object) forgeEvent.invokeExact(resources, access);
            ForgeGameEvents.post(bus, forge);
            Set<Object> added = Collections.newSetFromMap(new IdentityHashMap<>());
            List<?> all = (List<?>) listeners.invokeExact(forge);
            for (Object listener : all) {
                Object own = wrapper.isInstance(listener) ? (Object) wrapped.invokeExact(listener) : listener;
                if (added.add(own)) {
                    Object name = (Object) identifier.invokeExact(NAMESPACE, "reload_listener_" + added.size());
                    addListener.invokeExact((Object) event, name, own);
                }
            }
            log.accept("Forge on NeoForge: " + added.size() + " Forge reload listener(s) added to NeoForge's server"
                    + " reload");
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
