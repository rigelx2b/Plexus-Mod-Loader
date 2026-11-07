package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.internal.transform.MethodTarget;
import java.util.List;

/**
 * Methods that MinecraftForge's patch made {@code final} and that Fabric and Quilt mods may override as on vanilla
 * (ADR-0047), each read with {@code javap} on the patched jar and the vanilla jar. Both Forge's access transformer
 * ({@code public+f}) and Forge's binary patch make them {@code final}; the runtime opens one of them only for a mod
 * with a class that overrides it.
 */
final class ForgeFinalMethods {

    /**
     * {@code Ingredient.toNetwork(FriendlyByteBuf)}: final in Forge 47.4.28 and 49.2.13, not in vanilla 1.20.1 and
     * 1.20.4 (javap).
     */
    static final MethodTarget INGREDIENT_TO_NETWORK = new MethodTarget(
            "net/minecraft/world/item/crafting/Ingredient", "toNetwork", "(Lnet/minecraft/network/FriendlyByteBuf;)V");

    private ForgeFinalMethods() {}

    /**
     * The reviewed methods. For {@code Ingredient.toNetwork(FriendlyByteBuf)}, Forge 47.4.28 writes an {@code
     * Ingredient} subclass ({@code isVanilla} {@code false}) through {@code CraftingHelper.write}, whose {@code
     * getSerializer()} throws {@code IllegalStateException} unless the subclass overrides it, whereas in Forge 49.2.13
     * {@code ForgeHooks.ingredientToNetwork} decides. Fabric API's {@code CustomIngredientImpl} overrides {@code
     * toNetwork} to write its own format, as on vanilla, while Forge's own and vanilla ingredients keep Forge's code.
     * Its fallback for a client without Fabric's custom ingredient sync calls Forge's {@code toNetwork}, which still
     * throws there for Forge 47 (a limit ADR-0047 records).
     */
    static List<MethodTarget> all() {
        return List.of(INGREDIENT_TO_NETWORK);
    }
}
