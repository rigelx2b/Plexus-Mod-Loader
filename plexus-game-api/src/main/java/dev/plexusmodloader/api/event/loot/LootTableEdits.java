package dev.plexusmodloader.api.event.loot;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Ready-made {@link LootModifyListener}s for the usual loot table edits (since 2.1.0): add a pool, remove an item,
 * replace an item. Register the result for {@link LootEvents#MODIFY}.
 *
 * <p>They edit <em>what a roll produced</em>, as {@code LootEvents.MODIFY} does. A pool added here is rolled after the
 * table and its items join the drops; an item removed here disappears from the drops. That differs from deleting a
 * pool or entry in the table file: removing an entry there makes the game pick another entry in its place, while
 * removing the item here only takes the stacks out. The listeners run for every roll of the table, so a chest that is
 * rolled again gets the edit again.
 *
 * <p><b>Thread:</b> the server thread. <b>Failure:</b> a null argument throws {@link NullPointerException}; an item ID
 * that is not registered throws {@link IllegalArgumentException} when a roll first uses it (the listener does not log
 * and skip, because it cannot know the registry before the game runs).
 */
@ApiStability(StabilityLevel.PREVIEW)
public final class LootTableEdits {

    private LootTableEdits() {}

    /**
     * Adds a pool to a loot table.
     *
     * @param table the loot table's ID, for example {@code minecraft:entities/zombie}
     * @param pool the pool to roll
     */
    public static LootModifyListener addPool(ResourceId table, LootPool pool) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(pool, "pool");
        return (id, drops) -> {
            if (!id.equals(table)) {
                return;
            }
            RandomGenerator random = drops.random();
            int rolls = pool.minRolls() + random.nextInt(pool.maxRolls() - pool.minRolls() + 1);
            int totalWeight =
                    pool.entries().stream().mapToInt(LootPool.Entry::weight).sum();
            for (int i = 0; i < rolls; i++) {
                if (random.nextDouble() >= pool.chance()) {
                    continue;
                }
                int pick = random.nextInt(totalWeight);
                for (LootPool.Entry entry : pool.entries()) {
                    pick -= entry.weight();
                    if (pick < 0) {
                        int count = entry.minCount() + random.nextInt(entry.maxCount() - entry.minCount() + 1);
                        drops.add(entry.item(), count);
                        break;
                    }
                }
            }
        };
    }

    /**
     * Removes an item from the drops of a loot table.
     *
     * @param table the loot table's ID, or {@code null} for every loot table
     * @param item the item to remove
     */
    public static LootModifyListener removeItem(ResourceId table, ResourceId item) {
        Objects.requireNonNull(item, "item");
        return (id, drops) -> {
            if (table == null || id.equals(table)) {
                drops.removeIf(stack -> stack.item().equals(item));
            }
        };
    }

    /**
     * Replaces an item in the drops of a loot table by another item, keeping the counts.
     *
     * @param table the loot table's ID, or {@code null} for every loot table
     * @param from the item to replace
     * @param to the item that takes its place
     */
    public static LootModifyListener replaceItem(ResourceId table, ResourceId from, ResourceId to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return (id, drops) -> {
            if (table == null || id.equals(table)) {
                int count = drops.count(from);
                if (count > 0) {
                    drops.removeIf(stack -> stack.item().equals(from));
                    drops.add(to, count);
                }
            }
        };
    }
}
