package dev.plexusmodloader.api.event.loot;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.List;
import java.util.Objects;

/**
 * A pool a mod adds to loot tables with {@link LootTableEdits#addPool}: some rolls, each with a chance, picks one
 * weighted entry and gives a random count of its item. It mirrors a vanilla loot pool in plain data.
 *
 * @param minRolls the fewest rolls of the pool (0 or more)
 * @param maxRolls the most rolls (at least {@code minRolls})
 * @param chance the chance, from 0 to 1, that each roll gives anything
 * @param entries the entries a roll picks from; at least one
 */
@ApiStability(StabilityLevel.PREVIEW)
public record LootPool(int minRolls, int maxRolls, double chance, List<Entry> entries) {

    /**
     * One entry of a pool.
     *
     * @param item the item it gives
     * @param weight how likely it is relative to the other entries (at least 1)
     * @param minCount the fewest items (at least 1)
     * @param maxCount the most items (at least {@code minCount})
     */
    @ApiStability(StabilityLevel.PREVIEW)
    public record Entry(ResourceId item, int weight, int minCount, int maxCount) {

        public Entry {
            Objects.requireNonNull(item, "item");
            if (weight < 1) {
                throw new IllegalArgumentException("weight must be at least 1: " + weight);
            }
            if (minCount < 1 || maxCount < minCount) {
                throw new IllegalArgumentException(
                        "counts must satisfy 1 <= min <= max: " + minCount + ", " + maxCount);
            }
        }

        /** An entry that always gives exactly {@code count} of {@code item} with weight 1. */
        public static Entry of(ResourceId item, int count) {
            return new Entry(item, 1, count, count);
        }
    }

    public LootPool {
        if (minRolls < 0 || maxRolls < minRolls) {
            throw new IllegalArgumentException("rolls must satisfy 0 <= min <= max: " + minRolls + ", " + maxRolls);
        }
        if (chance < 0 || chance > 1) {
            throw new IllegalArgumentException("chance must be from 0 to 1: " + chance);
        }
        entries = List.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("a pool needs at least one entry");
        }
    }

    /** A pool with exactly one roll that always succeeds. */
    public static LootPool single(Entry... entries) {
        return new LootPool(1, 1, 1.0, List.of(entries));
    }
}
