package dev.plexusmodloader.api.event.loot;

import dev.plexusmodloader.api.inventory.ItemStackView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.List;
import java.util.function.Predicate;

/**
 * The items one roll of a loot table produced, editable by a {@link LootModifyListener}. Items are plain stacks
 * (an item and a count); data on the rolled stacks, such as names and enchantments, is kept for items the
 * listener leaves alone and is not available to read.
 *
 * <p><b>Thread:</b> the server thread, during the listener call only. <b>Failure:</b> an item ID that is not
 * registered, or a count below 1, throws {@link IllegalArgumentException}.
 */
@ApiStability(StabilityLevel.PREVIEW)
public interface LootDrops {

    /** A copy of the current items, one entry per stack. */
    List<ItemStackView> items();

    /** Adds {@code count} of {@code item}; counts above the item's stack size become several stacks. */
    void add(ResourceId item, int count);

    /**
     * Removes every stack the filter accepts.
     *
     * @return how many stacks were removed
     */
    int removeIf(Predicate<ItemStackView> filter);

    /**
     * The random source of this roll: the game's own for the loot context, so a roll with a fixed loot seed (a chest)
     * gives the same added items every time. Use it for chances instead of {@code Math.random()}.
     */
    java.util.random.RandomGenerator random();

    /** Removes all items. */
    default void clear() {
        removeIf(stack -> true);
    }

    /** The total count of {@code item} in the rolled items. */
    default int count(ResourceId item) {
        int total = 0;
        for (ItemStackView stack : items()) {
            if (stack.item().equals(item)) {
                total += stack.count();
            }
        }
        return total;
    }
}
