package dev.plexusmodloader.api.content.item;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** The kind of a tool item: what it mines fast and how it behaves like the vanilla tool of that name. */
@ApiStability(StabilityLevel.PREVIEW)
public enum ToolKind {
    /** Cobwebs and bamboo mine fast; the best weapon. Damage defaults to 3, attack speed to -2.4. */
    SWORD(3.0f, -2.4f),
    /** Stone and ores. Damage defaults to 1, attack speed to -2.8. */
    PICKAXE(1.0f, -2.8f),
    /** Wood. Damage defaults to 6, attack speed to -3.1. */
    AXE(6.0f, -3.1f),
    /** Dirt, sand, snow. Damage defaults to 1.5, attack speed to -3.0. */
    SHOVEL(1.5f, -3.0f),
    /** Leaves and hay, tills dirt. Damage defaults to 0, attack speed to -1.0. */
    HOE(0.0f, -1.0f);

    private final float defaultAttackDamage;
    private final float defaultAttackSpeed;

    ToolKind(float defaultAttackDamage, float defaultAttackSpeed) {
        this.defaultAttackDamage = defaultAttackDamage;
        this.defaultAttackSpeed = defaultAttackSpeed;
    }

    /** The attack damage of the vanilla tool of this kind, before the material's bonus is added. */
    public float defaultAttackDamage() {
        return defaultAttackDamage;
    }

    /** The attack speed modifier of the vanilla tool of this kind (added to the base speed of 4). */
    public float defaultAttackSpeed() {
        return defaultAttackSpeed;
    }
}
