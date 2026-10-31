package dev.plexusmodloader.compat.forge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fabric API's {@code RegistryEntryAddedCallback} for what Fabric mods register into Forge's registries (ADR-0021 G8).
 * Fabric API's registry sync fires the event from a mixin at the return of {@code MappedRegistry.register}, but Forge
 * wraps the game's registries it manages in {@code NamespacedWrapper}, whose {@code register} goes to {@code
 * ForgeRegistry.add} and never to {@code MappedRegistry}'s (checked with {@code javap -c} on Forge 26.2-65.1.5). Fabric
 * API's menu module, for one, learns the stream codecs of extended menu types from that event; without it, opening such
 * a menu stopped the server ({@code Codec for ... is not registered!}).
 *
 * <p>The entries of each wrapper are noted before the other loaders' initialization; once it has run, the event fires
 * for each entry added since, in each registry's order, with its raw ID and ID, while the registries are still open.
 * Entries the listeners add in turn get the event too. A registry without the event (Fabric API's registry sync not
 * loaded) is left alone. Entries Forge adds later, in its own registration, do not get the event.
 *
 * <p>The event is reached by name, through reflection, from the registry: {@code fabric_getAddObjectEvent()} (Fabric
 * API's {@code ListenableRegistry}), the event's {@code invoker()} and {@code onEntryAdded(int, Identifier, Object)};
 * the registry's {@code getId(Object)} and {@code getKey(Object)}. Thread: the game's main thread, at Minecraft's mod
 * initialization point.
 */
final class ForgeRegistryAdditions {

    static final String ADD_OBJECT_EVENT = "fabric_getAddObjectEvent";

    private ForgeRegistryAdditions() {}

    /**
     * {@code initialization}, followed by Fabric API's entry-added event for each entry it added to a registry Forge
     * wraps.
     */
    static Runnable firingAfter(ClassLoader game, Runnable initialization) {
        return () -> {
            Map<Object, Set<Object>> before = snapshot(wrappers(game));
            initialization.run();
            fire(before);
        };
    }

    /** The game registries Forge wraps, from {@code BuiltInRegistries.REGISTRY}. */
    private static List<Object> wrappers(ClassLoader game) {
        try {
            Class<?> wrapper = Class.forName(ForgeRegistryOpening.NAMESPACED_WRAPPER, false, game);
            Iterable<?> root = (Iterable<?>) Class.forName(ForgeRegistryOpening.BUILT_IN_REGISTRIES, true, game)
                    .getField("REGISTRY")
                    .get(null);
            List<Object> found = new ArrayList<>();
            for (Object registry : root) {
                if (wrapper.isInstance(registry)) {
                    found.add(registry);
                }
            }
            return found;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Forge's wrapped registries could not be read (" + e + ")", e);
        }
    }

    private static Map<Object, Set<Object>> snapshot(List<Object> registries) {
        Map<Object, Set<Object>> entries = new IdentityHashMap<>();
        for (Object registry : registries) {
            entries.put(registry, entriesOf(registry));
        }
        return entries;
    }

    private static Set<Object> entriesOf(Object registry) {
        Set<Object> entries = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object entry : (Iterable<?>) registry) {
            entries.add(entry);
        }
        return entries;
    }

    /** Fires the event for the entries added since {@code seen}, until the listeners add no more. */
    static void fire(Map<Object, Set<Object>> seen) {
        for (Map.Entry<Object, Set<Object>> registry : seen.entrySet()) {
            Method eventGetter = method(registry.getKey().getClass(), ADD_OBJECT_EVENT);
            if (eventGetter == null) {
                continue;
            }
            Set<Object> fired = registry.getValue();
            List<Object> added = addedSince(registry.getKey(), fired);
            while (!added.isEmpty()) {
                for (Object entry : added) {
                    fired.add(entry);
                    entryAdded(registry.getKey(), eventGetter, entry);
                }
                added = addedSince(registry.getKey(), fired);
            }
        }
    }

    private static List<Object> addedSince(Object registry, Set<Object> fired) {
        List<Object> added = new ArrayList<>();
        for (Object entry : (Iterable<?>) registry) {
            if (!fired.contains(entry)) {
                added.add(entry);
            }
        }
        return added;
    }

    private static void entryAdded(Object registry, Method eventGetter, Object entry) {
        try {
            Object event = eventGetter.invoke(registry);
            Object invoker = event.getClass().getMethod("invoker").invoke(event);
            Method getId = method(registry.getClass(), "getId", Object.class);
            Method getKey = method(registry.getClass(), "getKey", Object.class);
            Method onEntryAdded = onEntryAdded(invoker.getClass());
            if (getId == null || getKey == null || onEntryAdded == null) {
                throw new IllegalStateException("Fabric API's entry-added event or the registry's getId/getKey of "
                        + registry + " is not the shape Plexus Mod Loader calls");
            }
            onEntryAdded.invoke(invoker, getId.invoke(registry, entry), getKey.invoke(registry, entry), entry);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Fabric API's entry-added event of " + registry + " could not be fired (" + e + ")", e);
        }
    }

    /** The invoker's {@code onEntryAdded(int, Identifier, Object)}, through the interface that declares it. */
    private static Method onEntryAdded(Class<?> invoker) {
        for (Class<?> type = invoker; type != null; type = type.getSuperclass()) {
            for (Class<?> declared : type.getInterfaces()) {
                for (Method candidate : declared.getMethods()) {
                    if (candidate.getName().equals("onEntryAdded")
                            && candidate.getParameterCount() == 3
                            && candidate.getParameterTypes()[0] == int.class) {
                        candidate.setAccessible(true);
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** A public method of {@code type} or its supertypes, or {@code null}. */
    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
