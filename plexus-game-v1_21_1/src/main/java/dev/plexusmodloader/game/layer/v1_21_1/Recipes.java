package dev.plexusmodloader.game.layer.v1_21_1;

import dev.plexusmodloader.api.recipe.RecipeKind;
import dev.plexusmodloader.api.recipe.RecipeLookup;
import dev.plexusmodloader.api.recipe.RecipeResult;
import dev.plexusmodloader.api.resource.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;

/** {@link RecipeLookup} over the level's recipe manager (1.21.1: recipes take a {@code RecipeInput}). */
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
        List<ItemStack> stacks = new ArrayList<>(items.size());
        for (ResourceId item : items) {
            stacks.add(new ItemStack(Views.item(item)));
        }
        CraftingInput grid = CraftingInput.of(width, height, stacks);
        return level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, grid, level)
                .map(holder -> result(holder, grid));
    }

    @Override
    public Optional<RecipeResult> process(RecipeKind kind, ResourceId input) {
        SingleRecipeInput single = new SingleRecipeInput(new ItemStack(Views.item(input)));
        return switch (kind) {
            case SMELTING -> single(RecipeType.SMELTING, single);
            case BLASTING -> single(RecipeType.BLASTING, single);
            case SMOKING -> single(RecipeType.SMOKING, single);
            case CAMPFIRE_COOKING -> single(RecipeType.CAMPFIRE_COOKING, single);
            case STONECUTTING -> single(RecipeType.STONECUTTING, single);
        };
    }

    private <T extends Recipe<SingleRecipeInput>> Optional<RecipeResult> single(
            RecipeType<T> type, SingleRecipeInput input) {
        return level.getRecipeManager().getRecipeFor(type, input, level).map(holder -> result(holder, input));
    }

    private <I extends RecipeInput> RecipeResult result(RecipeHolder<? extends Recipe<I>> holder, I input) {
        ItemStack made = holder.value().assemble(input, level.registryAccess());
        return new RecipeResult(
                Views.id(holder.id()),
                Views.id(BuiltInRegistries.ITEM.getKey(made.getItem())),
                Math.max(1, made.getCount()));
    }

    @Override
    public List<RecipeResult> producing(ResourceId item) {
        net.minecraft.world.item.Item target = Views.item(item);
        List<RecipeResult> found = new ArrayList<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            ItemStack made = holder.value().getResultItem(level.registryAccess());
            if (made.is(target)) {
                found.add(new RecipeResult(Views.id(holder.id()), item, Math.max(1, made.getCount())));
            }
        }
        found.sort(java.util.Comparator.comparing(recipe ->
                recipe.recipeId().namespace() + ":" + recipe.recipeId().path()));
        return found;
    }

    @Override
    public List<ResourceId> all() {
        List<ResourceId> ids = new java.util.ArrayList<>();
        for (RecipeHolder<?> recipe : level.getRecipeManager().getRecipes()) {
            ids.add(Views.id(recipe.id()));
        }
        ids.sort(java.util.Comparator.comparing(id -> id.namespace() + ":" + id.path()));
        return ids;
    }

    @Override
    public Optional<List<List<ResourceId>>> ingredients(ResourceId recipeId) {
        return level.getRecipeManager().byKey(Views.id(recipeId)).map(found -> slots(found.value()));
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
