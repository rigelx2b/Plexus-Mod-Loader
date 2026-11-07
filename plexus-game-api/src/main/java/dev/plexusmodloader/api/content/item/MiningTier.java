package dev.plexusmodloader.api.content.item;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/**
 * Which blocks a tool drops items from: the strongest vanilla tool class whose blocks it can harvest
 * (for example {@link #IRON} harvests everything an iron pickaxe harvests, but not obsidian).
 */
@ApiStability(StabilityLevel.PREVIEW)
public enum MiningTier {
    /** Wooden and golden tools. */
    WOOD(0, "wooden"),
    /** Stone tools. */
    STONE(1, "stone"),
    /** Iron tools. */
    IRON(2, "iron"),
    /** Diamond tools. */
    DIAMOND(3, "diamond"),
    /** Netherite tools. */
    NETHERITE(4, "netherite");

    private final int level;
    private final String vanillaName;

    MiningTier(int level, String vanillaName) {
        this.level = level;
        this.vanillaName = vanillaName;
    }

    /** The vanilla harvest level, 0 (wood) to 4 (netherite). */
    public int level() {
        return level;
    }

    /** The vanilla tool material word of this tier, such as {@code iron} or {@code wooden}. */
    public String vanillaName() {
        return vanillaName;
    }
}
