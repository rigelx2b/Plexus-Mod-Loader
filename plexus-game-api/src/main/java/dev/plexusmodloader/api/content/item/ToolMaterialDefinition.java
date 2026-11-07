package dev.plexusmodloader.api.content.item;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;
import java.util.Optional;

/**
 * What a set of tools is made of: how long it lasts, how fast it mines, and what it can harvest. One
 * definition is shared by the tools of a mod ({@link ItemDefinition.Builder#tool}).
 *
 * <p>The game types behind this differ by version (a {@code Tier} before 1.21.11, a {@code ToolMaterial}
 * from 1.21.11); Plexus Mod Loader builds the right one, so the same definition works on every version.
 *
 * @param miningTier which blocks the tool drops items from
 * @param durability uses before the tool breaks (wood 59, stone 131, iron 250, diamond 1561)
 * @param miningSpeed mining speed on blocks the tool is good at (wood 2, stone 4, iron 6, diamond 8)
 * @param attackDamageBonus added to the tool kind's attack damage (wood 0, stone 1, iron 2, diamond 3)
 * @param enchantability how readily it gets good enchantments (wood 15, stone 5, iron 14, gold 22)
 * @param repairItem the item that repairs the tool at an anvil, or empty for none; vanilla or mod item
 */
@ApiStability(StabilityLevel.PREVIEW)
public record ToolMaterialDefinition(
        MiningTier miningTier,
        int durability,
        float miningSpeed,
        float attackDamageBonus,
        int enchantability,
        Optional<ResourceId> repairItem) {

    public ToolMaterialDefinition {
        Objects.requireNonNull(miningTier, "miningTier");
        Objects.requireNonNull(repairItem, "repairItem");
        if (durability < 1) {
            throw new IllegalArgumentException("durability must be >= 1: " + durability);
        }
        if (!(miningSpeed > 0)) {
            throw new IllegalArgumentException("miningSpeed must be > 0: " + miningSpeed);
        }
        if (attackDamageBonus < 0) {
            throw new IllegalArgumentException("attackDamageBonus must be >= 0: " + attackDamageBonus);
        }
        if (enchantability < 0) {
            throw new IllegalArgumentException("enchantability must be >= 0: " + enchantability);
        }
    }

    /** A material that cannot be repaired. */
    public static ToolMaterialDefinition of(
            MiningTier miningTier, int durability, float miningSpeed, float attackDamageBonus, int enchantability) {
        return new ToolMaterialDefinition(
                miningTier, durability, miningSpeed, attackDamageBonus, enchantability, Optional.empty());
    }

    /** A copy that an anvil repairs with {@code item}. */
    public ToolMaterialDefinition repairedWith(ResourceId item) {
        return new ToolMaterialDefinition(
                miningTier, durability, miningSpeed, attackDamageBonus, enchantability, Optional.of(item));
    }
}
