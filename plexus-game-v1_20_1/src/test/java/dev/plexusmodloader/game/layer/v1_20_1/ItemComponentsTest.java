package dev.plexusmodloader.game.layer.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.content.item.ComponentType;
import dev.plexusmodloader.api.content.item.ItemComponentKey;
import dev.plexusmodloader.api.data.DataTree;
import dev.plexusmodloader.api.inventory.ItemStackRef;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.game.layer.v1_20_1.content.ItemComponents;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Item components are tags of the stack's NBT, so they travel with the stack and are saved with it. */
class ItemComponentsTest {

    private static final ItemComponentKey<Integer> CHARGE =
            new ItemComponentKey<>(ResourceId.of("demo", "charge"), ComponentType.INTEGER);
    private static final ItemComponentKey<Boolean> ARMED =
            new ItemComponentKey<>(ResourceId.of("demo", "armed"), ComponentType.BOOLEAN);
    private static final ItemComponentKey<String> OWNER =
            new ItemComponentKey<>(ResourceId.of("demo", "owner"), ComponentType.STRING);
    private static final ItemComponentKey<DataTree> EXTRA =
            new ItemComponentKey<>(ResourceId.of("demo", "extra"), ComponentType.TREE);
    private static final ItemComponentKey<Integer> UNDECLARED =
            new ItemComponentKey<>(ResourceId.of("demo", "undeclared"), ComponentType.INTEGER);

    @BeforeAll
    static void declare() {
        LayerTestSupport.bootstrap();
        ItemComponents.install(List.of(CHARGE, ARMED, OWNER, EXTRA));
    }

    @Test
    void everyComponentTypeRoundTripsAndSurvivesSavingTheStack() {
        ItemStack stack = new ItemStack(Items.STICK);
        AtomicInteger changes = new AtomicInteger();
        ItemStackRef ref = ItemComponents.ref(stack, changes::incrementAndGet);

        ref.set(CHARGE, 42);
        ref.set(ARMED, true);
        ref.set(OWNER, "alex");
        DataTree extra = DataTree.create();
        extra.putInt("level", 3);
        ref.set(EXTRA, extra);
        assertEquals(4, changes.get(), "every change is reported");

        // Saved and loaded again, as a stack in a chest or a player's inventory is.
        CompoundTag saved = stack.save(new CompoundTag());
        ItemStackRef loaded = ItemComponents.ref(ItemStack.of(saved), () -> {});

        assertEquals(Optional.of(42), loaded.get(CHARGE));
        assertEquals(Optional.of(true), loaded.get(ARMED));
        assertEquals(Optional.of("alex"), loaded.get(OWNER));
        assertEquals(3, loaded.get(EXTRA).orElseThrow().getInt("level", 0));
    }

    @Test
    void aStoredTreeIsACopy() {
        ItemStack stack = new ItemStack(Items.STICK);
        ItemStackRef ref = ItemComponents.ref(stack, () -> {});
        DataTree tree = DataTree.create();
        tree.putInt("n", 1);
        ref.set(EXTRA, tree);
        tree.putInt("n", 2);
        assertEquals(1, ref.get(EXTRA).orElseThrow().getInt("n", 0));
    }

    @Test
    void removingTheLastComponentLeavesNoTagBehind() {
        ItemStack stack = new ItemStack(Items.STICK);
        ItemStackRef ref = ItemComponents.ref(stack, () -> {});
        ref.set(CHARGE, 1);
        assertTrue(stack.hasTag());
        ref.remove(CHARGE);
        assertEquals(Optional.empty(), ref.get(CHARGE));
        assertFalse(stack.hasTag() && stack.getTag().contains(ItemComponents.ROOT));
    }

    @Test
    void anUndeclaredComponentIsRefused() {
        ItemStackRef ref = ItemComponents.ref(new ItemStack(Items.STICK), () -> {});
        assertThrows(IllegalArgumentException.class, () -> ref.get(UNDECLARED));
        assertThrows(IllegalArgumentException.class, () -> ref.set(UNDECLARED, 1));
    }

    @Test
    void anEmptyStackHasNoComponentsAndCannotGetOne() {
        ItemStackRef ref = ItemComponents.ref(ItemStack.EMPTY, () -> {});
        assertEquals(Optional.empty(), ref.get(CHARGE));
        assertThrows(IllegalStateException.class, () -> ref.set(CHARGE, 1));
    }

    @Test
    void theCustomNameIsTheStacksHoverName() {
        ItemStack stack = new ItemStack(Items.STICK);
        ItemStackRef ref = ItemComponents.ref(stack, () -> {});
        assertEquals(Optional.empty(), ref.customName());
        ref.setCustomName("Wand");
        assertEquals(Optional.of("Wand"), ref.customName());
        ref.setCustomName(null);
        assertEquals(Optional.empty(), ref.customName());
    }

    @Test
    void damageIsClampedToTheItemsDurability() {
        ItemStack stack = new ItemStack(Items.IRON_PICKAXE);
        ItemStackRef ref = ItemComponents.ref(stack, () -> {});
        ref.setDamage(10_000);
        assertEquals(stack.getMaxDamage(), ref.damage());
        ref.setDamage(-5);
        assertEquals(0, ref.damage());
    }
}
