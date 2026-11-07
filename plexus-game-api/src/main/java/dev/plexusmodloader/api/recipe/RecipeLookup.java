package dev.plexusmodloader.api.recipe;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.List;
import java.util.Optional;

/**
 * Asks the game's loaded recipes what an input makes: the recipes of the vanilla game, of data packs, and of
 * mods, as the server has them after the last data reload. Get it from
 * {@link dev.plexusmodloader.api.world.WorldView#recipes()}.
 *
 * <p>The lookup runs the game's own matching code, so it answers exactly what a crafting table, furnace, or
 * stonecutter would do with the same input, including tags and data-driven conditions. {@link #craft} and
 * {@link #process} ask by <em>input</em>; {@link #producing} asks by the item a recipe makes.
 *
 * <p><b>Thread:</b> the server thread. <b>Failure:</b> an item ID that is not registered throws
 * {@link IllegalArgumentException}; no matching recipe is an empty result, not an error.
 */
@ApiStability(StabilityLevel.PREVIEW)
public interface RecipeLookup {

    /**
     * What a crafting grid makes.
     *
     * @param width the grid's width in slots (1 to 3)
     * @param height the grid's height in slots (1 to 3)
     * @param items the slots row by row, {@code width * height} entries; {@code minecraft:air} is an empty slot
     * @return the first matching crafting recipe's result, or empty
     * @throws IllegalArgumentException when the size does not fit the grid
     */
    Optional<RecipeResult> craft(int width, int height, List<ResourceId> items);

    /**
     * What a furnace, blast furnace, smoker, campfire, or stonecutter makes from one item.
     *
     * @return the first matching recipe's result, or empty. For {@link RecipeKind#STONECUTTING} a stone cutter
     *     can offer several results for one input; this returns the first the game finds
     */
    Optional<RecipeResult> process(RecipeKind kind, ResourceId input);

    /**
     * Lists the recipes that make an item, whatever machine they belong to (crafting table, furnace, stonecutter,
     * smithing table, and recipe types of mods): the reverse of {@link #craft} and {@link #process}.
     *
     * <p>A recipe counts when the item is among what the game shows as its result. The list has one entry per recipe,
     * sorted by recipe ID, with the count that recipe makes. It tells which recipes exist, not what they need; use
     * {@code craft} or {@code process} to try an input. Some special recipes (map cloning, firework rockets) make
     * results that depend on the input and appear with the result the game shows for them, or not at all.
     *
     * @param item the item a recipe makes
     * @return the matching recipes, empty when nothing makes the item
     * @throws IllegalArgumentException when the item is not registered
     */
    List<RecipeResult> producing(ResourceId item);

    /**
     * Lists the IDs of every recipe the server has loaded, of every machine and every mod, sorted by ID.
     *
     * <p>Use it to walk all recipes (for example to build a recipe book of your own) and then ask
     * {@link #ingredients} about each one.
     *
     * @return the recipe IDs, never null, empty only when no recipe is loaded
     */
    List<ResourceId> all();

    /**
     * Tells what one recipe needs, as the game describes it.
     *
     * <p>The outer list has one entry per ingredient slot the recipe needs (empty slots of a shaped recipe are
     * left out; a furnace recipe has one slot, a smithing recipe three). Each inner list holds the items that
     * satisfy that slot: one item for a plain ingredient, several for a tag or an alternative, in the game's
     * order. A slot with no items (an empty tag, or a dynamic ingredient the game cannot list) is left out.
     * Up to 1.21.1 the game keeps an {@code Ingredient} per slot; from 1.21.11 it shows slot displays. Both are
     * flattened to this shape.
     *
     * @param recipeId the recipe's ID, for example {@code minecraft:stick}
     * @return the slots, or empty when no loaded recipe has that ID or the recipe has no ingredient description
     */
    Optional<List<List<ResourceId>>> ingredients(ResourceId recipeId);
}
