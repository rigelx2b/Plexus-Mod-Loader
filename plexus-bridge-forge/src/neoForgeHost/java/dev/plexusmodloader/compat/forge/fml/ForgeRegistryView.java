package dev.plexusmodloader.compat.forge.fml;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;

/**
 * Forge on NeoForge, F-N4 (ADR-0051): a Forge {@code IForgeRegistry} that is a view of the game's registry under a key,
 * which the host hands out for the fields of Forge's {@code ForgeRegistries} ({@code ForgeRegistriesPatch} in the Forge
 * bridge). It is a {@link Proxy}: the host is not compiled against Minecraft, whose types the interface's methods take
 * (nor against the interface, whose type annotations name them).
 *
 * <p>What it answers, compared with Forge's own {@code ForgeRegistry} ({@code javap}, Forge {@code 26.2-65.1.5}): {@code
 * getRegistryKey} and {@code getRegistryName} from the key; {@code getValue}, {@code getKey}, {@code containsKey}, {@code
 * getResourceKey}, {@code getKeys} and {@code getEntries} from the game registry's own members of those names ({@code
 * getValue}, {@code keySet}, {@code entrySet}; a defaulted registry gives its default value and key for what it does not
 * have, as Forge's does); {@code getDefaultKey} from a defaulted registry, otherwise {@code null}; {@code containsValue}
 * and {@code isEmpty} from {@code getResourceKey} and {@code size}; {@code getValues} as a live collection of the
 * registry; {@code getHolder} from the registry's {@code get} (by key, by name, or by the value's key, like Forge's
 * holders by value); iteration from the registry. Forge's other members ({@code register}, {@code tags}, {@code
 * getSlaveMap}, {@code getCodec}, {@code getDelegate}, {@code getDelegateOrThrow}) throw: the runtime scope check leaves
 * out the Forge mods that use them.
 *
 * <p>The game registry is found the first time a member needs it (the fields are made when a Forge mod first reads
 * {@code ForgeRegistries}, possibly while it is constructed). Thread: any; the registry and its members are resolved once.
 */
final class ForgeRegistryView implements InvocationHandler {

    private static final String REGISTRY = "net.minecraft.core.Registry";
    private static final String DEFAULTED_REGISTRY = "net.minecraft.core.DefaultedRegistry";
    private static final String BUILT_IN_REGISTRIES = "net.minecraft.core.registries.BuiltInRegistries";

    private final Object key;
    private volatile Bound bound;

    private ForgeRegistryView(Object key) {
        this.key = key;
    }

    /**
     * A view of the game's registry under {@code key} (a {@code ResourceKey} of a registry), implementing Forge's {@code
     * forgeRegistry} interface ({@code IForgeRegistry}).
     */
    static Object of(Class<?> forgeRegistry, Object key) {
        return Proxy.newProxyInstance(
                forgeRegistry.getClassLoader(), new Class<?>[] {forgeRegistry}, new ForgeRegistryView(key));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        int count = method.getParameterCount();
        switch (name) {
            case "equals":
                if (count == 1) {
                    return proxy == args[0];
                }
                break;
            case "hashCode":
                if (count == 0) {
                    return System.identityHashCode(proxy);
                }
                break;
            case "toString":
                if (count == 0) {
                    return "Forge on NeoForge view of the game registry " + key;
                }
                break;
            case "getRegistryKey":
                return key;
            default:
                break;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        Bound game = bound();
        Object registry = game.registry;
        switch (name) {
            case "getRegistryName":
                return game.identifier.invokeExact(key);
            case "getValue":
                return game.getValue.invokeExact(registry, args[0]);
            case "getKey":
                return game.getKey.invokeExact(registry, args[0]);
            case "containsKey":
                return (boolean) game.containsKey.invokeExact(registry, args[0]);
            case "getResourceKey":
                return (Optional<?>) (Object) game.getResourceKey.invokeExact(registry, args[0]);
            case "containsValue":
                return ((Optional<?>) (Object) game.getResourceKey.invokeExact(registry, args[0])).isPresent();
            case "isEmpty":
                return (int) game.size.invokeExact(registry) == 0;
            case "getKeys":
                return (Set<?>) (Object) game.keySet.invokeExact(registry);
            case "getEntries":
                return (Set<?>) (Object) game.entrySet.invokeExact(registry);
            case "getValues":
                return game.values();
            case "iterator":
                return game.iterator();
            case "getDefaultKey":
                return game.getDefaultKey == null ? null : game.getDefaultKey.invokeExact(registry);
            case "getHolder":
                return game.holder(method.getParameterTypes()[0], args[0]);
            default:
                throw new UnsupportedOperationException("PLEXUS-COMPAT-229 IForgeRegistry." + name
                        + " is not supported by Forge on NeoForge's view of the game registry " + key);
        }
    }

    private Bound bound() {
        Bound current = bound;
        if (current == null) {
            synchronized (this) {
                current = bound;
                if (current == null) {
                    try {
                        current = new Bound(key);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        throw new IllegalStateException(
                                "PLEXUS-COMPAT-229 Forge on NeoForge cannot find the game registry " + key + ": " + e,
                                e);
                    }
                    bound = current;
                }
            }
        }
        return current;
    }

    /** The game registry under the key and the handles of its members, all as {@code Object}s. */
    private static final class Bound {

        private final Object registry;
        private final Class<?> resourceKey;
        private final MethodHandle identifier;
        private final MethodHandle getValue;
        private final MethodHandle getKey;
        private final MethodHandle containsKey;
        private final MethodHandle getResourceKey;
        private final MethodHandle size;
        private final MethodHandle keySet;
        private final MethodHandle entrySet;
        private final MethodHandle iterator;
        private final MethodHandle getByKey;
        private final MethodHandle getByName;
        private final MethodHandle getDefaultKey;

        Bound(Object key) throws ReflectiveOperationException {
            ClassLoader game = key.getClass().getClassLoader();
            resourceKey = key.getClass();
            Class<?> registryType = Class.forName(REGISTRY, false, game);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Method identifierMethod = resourceKey.getMethod("identifier");
            Class<?> identifierType = identifierMethod.getReturnType();
            identifier = generic(lookup.unreflect(identifierMethod));
            Object registries = Class.forName(BUILT_IN_REGISTRIES, true, game)
                    .getField("REGISTRY")
                    .get(null);
            MethodHandle registryValue =
                    lookup.findVirtual(registryType, "getValue", MethodType.methodType(Object.class, identifierType));
            getValue = generic(registryValue);
            Object found;
            try {
                found = getValue.invokeExact(registries, identifier.invokeExact(key));
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
            if (found == null) {
                throw new IllegalStateException("not among the game's built-in registries");
            }
            registry = found;
            getKey = generic(
                    lookup.findVirtual(registryType, "getKey", MethodType.methodType(identifierType, Object.class)));
            containsKey = generic(lookup.findVirtual(
                    registryType, "containsKey", MethodType.methodType(boolean.class, identifierType)));
            getResourceKey = generic(lookup.findVirtual(
                    registryType, "getResourceKey", MethodType.methodType(Optional.class, Object.class)));
            size = generic(lookup.findVirtual(registryType, "size", MethodType.methodType(int.class)));
            keySet = generic(lookup.findVirtual(registryType, "keySet", MethodType.methodType(Set.class)));
            entrySet = generic(lookup.findVirtual(registryType, "entrySet", MethodType.methodType(Set.class)));
            iterator = generic(lookup.findVirtual(registryType, "iterator", MethodType.methodType(Iterator.class)));
            getByKey = generic(
                    lookup.findVirtual(registryType, "get", MethodType.methodType(Optional.class, resourceKey)));
            getByName = generic(
                    lookup.findVirtual(registryType, "get", MethodType.methodType(Optional.class, identifierType)));
            Class<?> defaulted = Class.forName(DEFAULTED_REGISTRY, false, game);
            getDefaultKey = defaulted.isInstance(registry)
                    ? generic(lookup.findVirtual(defaulted, "getDefaultKey", MethodType.methodType(identifierType)))
                    : null;
        }

        /** {@code handle} with every reference type as {@code Object}. */
        private static MethodHandle generic(MethodHandle handle) {
            return handle.asType(handle.type()
                    .generic()
                    .changeReturnType(wrapped(handle.type().returnType())));
        }

        private static Class<?> wrapped(Class<?> type) {
            return type.isPrimitive() ? type : Object.class;
        }

        Optional<?> holder(Class<?> parameter, Object argument) throws Throwable {
            if (parameter == resourceKey) {
                return (Optional<?>) (Object) getByKey.invokeExact(registry, argument);
            }
            if (parameter.getName().endsWith(".Identifier")) {
                return (Optional<?>) (Object) getByName.invokeExact(registry, argument);
            }
            Optional<?> valueKey = (Optional<?>) (Object) getResourceKey.invokeExact(registry, argument);
            if (valueKey.isEmpty()) {
                return Optional.empty();
            }
            return (Optional<?>) (Object) getByKey.invokeExact(registry, (Object) valueKey.get());
        }

        Iterator<?> iterator() {
            try {
                return (Iterator<?>) (Object) iterator.invokeExact(registry);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        Collection<Object> values() {
            return new AbstractCollection<>() {
                @Override
                @SuppressWarnings("unchecked")
                public Iterator<Object> iterator() {
                    return (Iterator<Object>) Bound.this.iterator();
                }

                @Override
                public int size() {
                    try {
                        return (int) size.invokeExact(registry);
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Throwable e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
        }
    }
}
