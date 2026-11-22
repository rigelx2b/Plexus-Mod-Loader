package dev.plexusmodloader.compat.forge.fml;

import java.lang.reflect.Field;

/**
 * Forge on NeoForge, F-N4 (ADR-0051): what Forge's own classes call in place of members MinecraftForge's patch adds to
 * Minecraft and NeoForge's game does not have. The host's patch of Forge's {@code universal} jar ({@code
 * ForgeRegistryObjectPatch} in the Forge bridge) points Forge's call here; its patch of Forge's {@code ForgeRegistries}
 * ({@code ForgeRegistriesPatch}) points that class's registry set-up here, in place of Forge's own registries.
 *
 * <p>Thread: any; the field is resolved once.
 */
public final class ForgeRegistryHooks {

    private static final String MAPPED_REGISTRY = "net.minecraft.core.MappedRegistry";
    private static final String INTRUSIVE_HOLDERS = "unregisteredIntrusiveHolders";
    private static final String FORGE_REGISTRY = "net.minecraftforge.registries.IForgeRegistry";

    private static volatile Field intrusiveHolders;

    private ForgeRegistryHooks() {}

    /**
     * MinecraftForge's {@code MappedRegistry.isIntrusive()}: whether {@code registry} (a {@code MappedRegistry}) still
     * takes intrusive holders, that is, its {@code unregisteredIntrusiveHolders} is not {@code null}. NeoForge's game has
     * the same field.
     *
     * @throws IllegalStateException when the field is not there (another Minecraft release)
     */
    public static boolean isIntrusive(Object registry) {
        try {
            return field(registry.getClass()).get(registry) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(MAPPED_REGISTRY + "." + INTRUSIVE_HOLDERS + ": " + e, e);
        }
    }

    /**
     * In place of Forge's {@code ForgeRegistries.init()}, called from {@code ForgeRegistries}' static initializer: does
     * nothing. Forge's would make Forge's own registries ({@code GameData.init()}), apart from the game's; the game has
     * already bootstrapped, and {@code ForgeRegistries.Keys} initializes itself when it is read.
     */
    public static void initForgeRegistries() {}

    /**
     * In place of Forge's {@code ForgeRegistries.active(ResourceKey)}, called from {@code ForgeRegistries}' static
     * initializer for each of its {@code IForgeRegistry} fields: a view of the game's registry under {@code key} ({@code
     * ForgeRegistryView}), which finds that registry the first time a member needs it. The view implements the {@code
     * IForgeRegistry} of the calling {@code ForgeRegistries}' class loader; the patch casts the result to it.
     *
     * @throws IllegalStateException when that class loader has no {@code IForgeRegistry}
     */
    public static Object gameRegistry(Object key) {
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .getCallerClass();
        try {
            return ForgeRegistryView.of(Class.forName(FORGE_REGISTRY, false, caller.getClassLoader()), key);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(FORGE_REGISTRY + " from " + caller.getName() + ": " + e, e);
        }
    }

    private static Field field(Class<?> type) throws NoSuchFieldException {
        Field field = intrusiveHolders;
        if (field != null) {
            return field;
        }
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            if (owner.getName().equals(MAPPED_REGISTRY)) {
                field = owner.getDeclaredField(INTRUSIVE_HOLDERS);
                field.setAccessible(true);
                intrusiveHolders = field;
                return field;
            }
        }
        throw new NoSuchFieldException(type.getName() + " is not a " + MAPPED_REGISTRY);
    }
}
