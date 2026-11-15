package dev.plexusmodloader.compat.forge.fml;

import java.lang.reflect.Field;

/**
 * Forge on NeoForge, F-N4 (ADR-0051): what Forge's own classes call in place of members MinecraftForge's patch adds to
 * Minecraft and NeoForge's game does not have. The host's patch of Forge's {@code universal} jar ({@code
 * ForgeRegistryObjectPatch} in the Forge bridge) points Forge's call here.
 *
 * <p>Thread: any; the field is resolved once.
 */
public final class ForgeRegistryHooks {

    private static final String MAPPED_REGISTRY = "net.minecraft.core.MappedRegistry";
    private static final String INTRUSIVE_HOLDERS = "unregisteredIntrusiveHolders";

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
