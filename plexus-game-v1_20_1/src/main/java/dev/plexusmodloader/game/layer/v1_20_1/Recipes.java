package dev.plexusmodloader.game.layer.v1_20_1;

import dev.plexusmodloader.api.recipe.RecipeKind;
import dev.plexusmodloader.api.recipe.RecipeLookup;
import dev.plexusmodloader.api.recipe.RecipeResult;
import dev.plexusmodloader.api.resource.ResourceId;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

/** {@link RecipeLookup} over the level's recipe manager (1.20.1: recipes are plain objects with an ID). */
final class Recipes implements RecipeLookup {

    private final ServerLevel level;

    Recipes(ServerLevel level) {
        this.level = level;
    }

    @Override
    public Optional<RecipeResult> craft(int width, int height, List<ResourceId> items) {
        if (width < 1 || width > 3 || height < 1 || height > 3 || items.size() != width * height) {
            throw new IllegalArgumentException(
                    "A " + width + "x" + height + " grid needs " + width * height + " items, not " + items.size());
        }
        // The grid only holds the stacks; the menu is never opened.
        AbstractContainerMenu menu = new AbstractContainerMenu(null, -1) {
            @Override
            public ItemStack quickMoveStack(Player player, int index) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        };
        CraftingContainer grid = new TransientCraftingContainer(menu, width, height);
        for (int slot = 0; slot < items.size(); slot++) {
            grid.setItem(slot, new ItemStack(Views.item(items.get(slot))));
        }
        return level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, grid, level)
                .map(recipe -> result(recipe, grid));
    }

    @Override
    public Optional<RecipeResult> process(RecipeKind kind, ResourceId input) {
        Container container = new SimpleContainer(new ItemStack(Views.item(input)));
        return switch (kind) {
            case SMELTING -> single(RecipeType.SMELTING, container);
            case BLASTING -> single(RecipeType.BLASTING, container);
            case SMOKING -> single(RecipeType.SMOKING, container);
            case CAMPFIRE_COOKING -> single(RecipeType.CAMPFIRE_COOKING, container);
            case STONECUTTING -> single(RecipeType.STONECUTTING, container);
        };
    }

    private <T extends Recipe<Container>> Optional<RecipeResult> single(RecipeType<T> type, Container container) {
        return level.getRecipeManager().getRecipeFor(type, container, level).map(recipe -> result(recipe, container));
    }

    private <C extends Container> RecipeResult result(Recipe<C> recipe, C input) {
        ItemStack made = recipe.assemble(input, level.registryAccess());
        return new RecipeResult(
                Views.id(recipe.getId()),
                Views.id(BuiltInRegistries.ITEM.getKey(made.getItem())),
                Math.max(1, made.getCount()));
    }

    @Override
    public List<RecipeResult> producing(ResourceId item) {
        net.minecraft.world.item.Item target = Views.item(item);
        List<RecipeResult> found = new java.util.ArrayList<>();
        for (Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
            ItemStack made = recipe.getResultItem(level.registryAccess());
            if (made.is(target)) {
                found.add(new RecipeResult(Views.id(recipe.getId()), item, Math.max(1, made.getCount())));
            }
        }
        found.sort(java.util.Comparator.comparing(recipe ->
                recipe.recipeId().namespace() + ":" + recipe.recipeId().path()));
        return found;
    }

    @Override
    public List<ResourceId> all() {
        List<ResourceId> ids = new java.util.ArrayList<>();
        for (Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
            ids.add(Views.id(recipe.getId()));
        }
        ids.sort(java.util.Comparator.comparing(id -> id.namespace() + ":" + id.path()));
        return ids;
    }

    @Override
    public Optional<List<List<ResourceId>>> ingredients(ResourceId recipeId) {
        return level.getRecipeManager().byKey(Views.id(recipeId)).map(found -> slots(found));
    }

    private static List<List<ResourceId>> slots(Recipe<?> recipe) {
        List<List<ResourceId>> slots = new java.util.ArrayList<>();
        for (net.minecraft.world.item.crafting.Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            List<ResourceId> alternatives = new java.util.ArrayList<>();
            for (ItemStack stack : ingredient.getItems()) {
                ResourceId id = Views.id(BuiltInRegistries.ITEM.getKey(stack.getItem()));
                if (!alternatives.contains(id)) {
                    alternatives.add(id);
                }
            }
            slots.add(alternatives);
        }
        return slots;
    }
}
