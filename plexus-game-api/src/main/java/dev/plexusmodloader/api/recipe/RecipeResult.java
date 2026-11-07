package dev.plexusmodloader.api.recipe;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;

/**
 * What a recipe makes for a given input.
 *
 * @param recipeId the recipe's ID, for example {@code minecraft:iron_ingot_from_smelting_iron_ore}
 * @param item the item it makes
 * @param count how many items it makes (at least 1)
 */
@ApiStability(StabilityLevel.PREVIEW)
public record RecipeResult(ResourceId recipeId, ResourceId item, int count) {

    public RecipeResult {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(item, "item");
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1: " + count);
        }
    }
}
