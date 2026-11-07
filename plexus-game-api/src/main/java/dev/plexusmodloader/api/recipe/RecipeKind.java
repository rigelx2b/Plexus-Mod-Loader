package dev.plexusmodloader.api.recipe;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** The kinds of recipe that turn one item into another, for {@link RecipeLookup#process(RecipeKind, dev.plexusmodloader.api.resource.ResourceId)}. */
@ApiStability(StabilityLevel.PREVIEW)
public enum RecipeKind {
    /** Furnace smelting, {@code minecraft:smelting}. */
    SMELTING,
    /** Blast furnace, {@code minecraft:blasting}. */
    BLASTING,
    /** Smoker, {@code minecraft:smoking}. */
    SMOKING,
    /** Campfire, {@code minecraft:campfire_cooking}. */
    CAMPFIRE_COOKING,
    /** Stonecutter, {@code minecraft:stonecutting}. */
    STONECUTTING
}
