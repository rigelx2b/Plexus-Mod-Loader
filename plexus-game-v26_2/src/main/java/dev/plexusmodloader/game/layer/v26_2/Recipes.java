package dev.plexusmodloader.game.layer.v26_2;

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

/** {@link RecipeLookup} over the server's recipe manager (26.2: the manager hangs off the level as {@code recipeAccess()}). */
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
        return level.recipeAccess()
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
        return level.recipeAccess().getRecipeFor(type, input, level).map(holder -> result(holder, input));
    }

    private <I extends RecipeInput> RecipeResult result(RecipeHolder<? extends Recipe<I>> holder, I input) {
        ItemStack made = holder.value().assemble(input);
        return new RecipeResult(
                Views.id(holder.id().identifier()),
                Views.id(BuiltInRegistries.ITEM.getKey(made.getItem())),
                Math.max(1, made.getCount()));
    }

    @Override
    public List<RecipeResult> producing(ResourceId item) {
        net.minecraft.world.item.Item target = Views.item(item);
        List<RecipeResult> found = new ArrayList<>();
        net.minecraft.util.context.ContextMap context =
                net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(level);
        for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
            RecipeResult made = null;
            for (net.minecraft.world.item.crafting.display.RecipeDisplay display :
                    holder.value().display()) {
                for (ItemStack stack : display.result().resolveForStacks(context)) {
                    if (stack.is(target)) {
                        made = new RecipeResult(
                                Views.id(holder.id().identifier()), item, Math.max(1, stack.getCount()));
                        break;
                    }
                }
                if (made != null) {
                    break;
                }
            }
            if (made != null) {
                found.add(made);
            }
        }
        found.sort(java.util.Comparator.comparing(recipe ->
                recipe.recipeId().namespace() + ":" + recipe.recipeId().path()));
        return found;
    }

    @Override
    public List<ResourceId> all() {
        List<ResourceId> ids = new ArrayList<>();
        for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
            ids.add(Views.id(holder.id().identifier()));
        }
        ids.sort(java.util.Comparator.comparing(id -> id.namespace() + ":" + id.path()));
        return ids;
    }

    @Override
    public Optional<List<List<ResourceId>>> ingredients(ResourceId recipeId) {
        net.minecraft.util.context.ContextMap context =
                net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(level);
        for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
            if (!Views.id(holder.id().identifier()).equals(recipeId)) {
                continue;
            }
            for (net.minecraft.world.item.crafting.display.RecipeDisplay display :
                    holder.value().display()) {
                List<net.minecraft.world.item.crafting.display.SlotDisplay> displays = ingredientDisplays(display);
                if (displays == null) {
                    continue;
                }
                List<List<ResourceId>> slots = new ArrayList<>();
                for (net.minecraft.world.item.crafting.display.SlotDisplay slot : displays) {
                    List<ResourceId> alternatives = new ArrayList<>();
                    for (ItemStack stack : slot.resolveForStacks(context)) {
                        ResourceId id = Views.id(BuiltInRegistries.ITEM.getKey(stack.getItem()));
                        if (!alternatives.contains(id)) {
                            alternatives.add(id);
                        }
                    }
                    if (!alternatives.isEmpty()) {
                        slots.add(alternatives);
                    }
                }
                return Optional.of(slots);
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** The ingredient slots of the display kinds the game ships; null for a kind this layer does not know. */
    private static List<net.minecraft.world.item.crafting.display.SlotDisplay> ingredientDisplays(
            net.minecraft.world.item.crafting.display.RecipeDisplay display) {
        if (display instanceof net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay shaped) {
            return shaped.ingredients();
        } else if (display instanceof net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay loose) {
            return loose.ingredients();
        } else if (display instanceof net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay furnace) {
            return List.of(furnace.ingredient());
        } else if (display instanceof net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay cutter) {
            return List.of(cutter.input());
        } else if (display instanceof net.minecraft.world.item.crafting.display.SmithingRecipeDisplay smithing) {
            return List.of(smithing.template(), smithing.base(), smithing.addition());
        }
        return null;
    }
}
