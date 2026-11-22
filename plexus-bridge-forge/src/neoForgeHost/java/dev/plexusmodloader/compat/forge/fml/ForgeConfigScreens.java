package dev.plexusmodloader.compat.forge.fml;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Forge on NeoForge, F-N6 (ADR-0051): a Forge mod's config screen in NeoForge's mod list. A Forge mod registers Forge's
 * {@code ConfigScreenHandler.ConfigScreenFactory} as an extension point of its Forge mod container (directly, or through
 * {@code MinecraftForge.registerConfigScreen}); NeoForge's mod list asks the mod's NeoForge container for NeoForge's
 * {@code IConfigScreenFactory} ({@code IConfigScreenFactory.getForMod}). The host's container answers that with a view of
 * Forge's factory: {@code createScreen(container, parent)} is Forge's {@code screenFunction().apply(Minecraft, parent)}
 * with the game's {@code Minecraft.getInstance()}.
 *
 * <p>Forge's factory names Minecraft's client classes in its signatures, and NeoForge's interface and {@code Minecraft}
 * are in NeoForge's {@code universal} jar and the game, none of which this source set is compiled against: all of them
 * are found by name in the game class loader and used through {@link MethodHandles#publicLookup()}, and the view is a
 * {@link Proxy} of NeoForge's interface.
 *
 * <p>Thread: the client thread (NeoForge's mod list); stateless.
 */
final class ForgeConfigScreens {

    static final String NEOFORGE_FACTORY = "net.neoforged.neoforge.client.gui.IConfigScreenFactory";
    static final String FORGE_FACTORY = "net.minecraftforge.client.ConfigScreenHandler$ConfigScreenFactory";
    static final String MINECRAFT = "net.minecraft.client.Minecraft";

    private ForgeConfigScreens() {}

    /** Whether {@code point} is NeoForge's {@code IConfigScreenFactory}. */
    static boolean isConfigScreenFactory(Class<?> point) {
        return point.getName().equals(NEOFORGE_FACTORY);
    }

    /**
     * NeoForge's {@code IConfigScreenFactory} ({@code point}) over the Forge mod container {@code forge}'s Forge config
     * screen factory, when it has one.
     *
     * @throws IllegalStateException when Forge's factory or the game's {@code Minecraft.getInstance()} is not there
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Optional<Object> of(net.minecraftforge.fml.ModContainer forge, Class<?> point) {
        ClassLoader game = point.getClassLoader();
        try {
            Class forgeFactory = Class.forName(FORGE_FACTORY, false, game);
            Optional<?> registered = forge.getCustomExtension(forgeFactory);
            if (registered.isEmpty()) {
                return Optional.empty();
            }
            MethodHandle screenFunction = MethodHandles.publicLookup()
                    .findVirtual(forgeFactory, "screenFunction", MethodType.methodType(BiFunction.class));
            BiFunction<Object, Object, Object> function =
                    (BiFunction<Object, Object, Object>) screenFunction.invoke(registered.get());
            return Optional.of(view(point, function, minecraft(game)));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot pass a Forge mod's config screen on to NeoForge: " + e, e);
        }
    }

    private static Object view(Class<?> point, BiFunction<Object, Object, Object> screen, MethodHandle minecraft) {
        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "createScreen" -> screen.apply((Object) minecraft.invokeExact(), arguments[1]);
            case "toString" -> "Forge config screen factory " + screen;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> defaultMethod(proxy, method, arguments);
        };
        return Proxy.newProxyInstance(point.getClassLoader(), new Class<?>[] {point}, handler);
    }

    private static Object defaultMethod(Object proxy, Method method, Object[] arguments) throws Throwable {
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, arguments);
        }
        throw new UnsupportedOperationException(
                "PLEXUS-COMPAT-229 NeoForge's IConfigScreenFactory." + method.getName() + " has no Forge counterpart");
    }

    private static MethodHandle minecraft(ClassLoader game) throws ReflectiveOperationException {
        Class<?> type = Class.forName(MINECRAFT, false, game);
        return MethodHandles.publicLookup()
                .findStatic(type, "getInstance", MethodType.methodType(type))
                .asType(MethodType.methodType(Object.class));
    }
}
