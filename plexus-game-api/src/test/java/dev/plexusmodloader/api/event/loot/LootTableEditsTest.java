package dev.plexusmodloader.api.event.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.inventory.ItemStackView;
import dev.plexusmodloader.api.resource.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class LootTableEditsTest {

    private static final ResourceId CHEST = ResourceId.minecraft("chests/test");
    private static final ResourceId OTHER = ResourceId.minecraft("chests/other");
    private static final ResourceId APPLE = ResourceId.minecraft("apple");
    private static final ResourceId STICK = ResourceId.minecraft("stick");
    private static final ResourceId COAL = ResourceId.minecraft("coal");

    /** Drops over a plain list, with a seeded random. */
    private static final class FakeDrops implements LootDrops {
        final List<ItemStackView> stacks = new ArrayList<>();
        final RandomGenerator random = new Random(42);

        @Override
        public List<ItemStackView> items() {
            return List.copyOf(stacks);
        }

        @Override
        public void add(ResourceId item, int count) {
            stacks.add(new ItemStackView(item, count));
        }

        @Override
        public int removeIf(Predicate<ItemStackView> filter) {
            int before = stacks.size();
            stacks.removeIf(filter);
            return before - stacks.size();
        }

        @Override
        public RandomGenerator random() {
            return random;
        }
    }

    @Test
    void addPoolRollsOnlyTheNamedTable() {
        LootModifyListener listener = LootTableEdits.addPool(CHEST, LootPool.single(new LootPool.Entry(COAL, 1, 2, 4)));
        FakeDrops here = new FakeDrops();
        FakeDrops elsewhere = new FakeDrops();

        listener.onModify(CHEST, here);
        listener.onModify(OTHER, elsewhere);

        assertEquals(1, here.stacks.size());
        assertTrue(here.count(COAL) >= 2 && here.count(COAL) <= 4, here.stacks.toString());
        assertTrue(elsewhere.stacks.isEmpty());
    }

    @Test
    void addPoolWithZeroChanceGivesNothing() {
        LootModifyListener listener =
                LootTableEdits.addPool(CHEST, new LootPool(1, 3, 0.0, List.of(LootPool.Entry.of(COAL, 1))));
        FakeDrops drops = new FakeDrops();

        listener.onModify(CHEST, drops);

        assertTrue(drops.stacks.isEmpty());
    }

    @Test
    void addPoolPicksEntriesByWeight() {
        LootModifyListener listener = LootTableEdits.addPool(
                CHEST,
                new LootPool(
                        200, 200, 1.0, List.of(new LootPool.Entry(COAL, 9, 1, 1), new LootPool.Entry(STICK, 1, 1, 1))));
        FakeDrops drops = new FakeDrops();

        listener.onModify(CHEST, drops);

        assertEquals(200, drops.count(COAL) + drops.count(STICK));
        assertTrue(drops.count(COAL) > drops.count(STICK) * 3, drops.stacks.toString());
    }

    @Test
    void removeItemKeepsTheOtherStacksAndHonoursTheTable() {
        FakeDrops drops = new FakeDrops();
        drops.add(APPLE, 2);
        drops.add(STICK, 1);

        LootTableEdits.removeItem(CHEST, APPLE).onModify(OTHER, drops);
        assertEquals(2, drops.stacks.size());
        LootTableEdits.removeItem(CHEST, APPLE).onModify(CHEST, drops);
        assertEquals(List.of(new ItemStackView(STICK, 1)), drops.stacks);
        drops.add(STICK, 4);
        LootTableEdits.removeItem(null, STICK).onModify(OTHER, drops);
        assertTrue(drops.stacks.isEmpty());
    }

    @Test
    void replaceItemKeepsTheTotalCount() {
        FakeDrops drops = new FakeDrops();
        drops.add(APPLE, 2);
        drops.add(APPLE, 3);
        drops.add(STICK, 1);

        LootTableEdits.replaceItem(CHEST, APPLE, COAL).onModify(CHEST, drops);

        assertEquals(0, drops.count(APPLE));
        assertEquals(5, drops.count(COAL));
        assertEquals(1, drops.count(STICK));
    }

    @Test
    void invalidPoolsAreRefused() {
        assertThrows(
                IllegalArgumentException.class, () -> new LootPool(2, 1, 1.0, List.of(LootPool.Entry.of(COAL, 1))));
        assertThrows(
                IllegalArgumentException.class, () -> new LootPool(1, 1, 1.5, List.of(LootPool.Entry.of(COAL, 1))));
        assertThrows(IllegalArgumentException.class, () -> new LootPool(1, 1, 1.0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LootPool.Entry(COAL, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LootPool.Entry(COAL, 1, 3, 2));
        assertThrows(
                NullPointerException.class,
                () -> LootTableEdits.addPool(null, LootPool.single(LootPool.Entry.of(COAL, 1))));
    }
}
