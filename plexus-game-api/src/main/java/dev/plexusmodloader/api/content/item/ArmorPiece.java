package dev.plexusmodloader.api.content.item;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Which part of a suit of armour an item is. */
@ApiStability(StabilityLevel.PREVIEW)
public enum ArmorPiece {
    /** Worn on the head. */
    HELMET(11),
    /** Worn on the body. */
    CHESTPLATE(16),
    /** Worn on the legs. */
    LEGGINGS(15),
    /** Worn on the feet. */
    BOOTS(13);

    private final int baseDurability;

    ArmorPiece(int baseDurability) {
        this.baseDurability = baseDurability;
    }

    /** The durability of this piece for a material with durability multiplier 1 (vanilla values). */
    public int baseDurability() {
        return baseDurability;
    }
}
