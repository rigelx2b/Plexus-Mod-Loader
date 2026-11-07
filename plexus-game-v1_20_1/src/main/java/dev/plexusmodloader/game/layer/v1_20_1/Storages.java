package dev.plexusmodloader.game.layer.v1_20_1;

import dev.plexusmodloader.api.inventory.ItemStackView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.transfer.FluidStorage;
import dev.plexusmodloader.api.transfer.ItemStorage;
import java.util.Optional;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The item and fluid storages of {@code WorldView.itemStorage} and {@code fluidStorage}. Item storage wraps the
 * game's own {@link Container}, found the way a hopper finds it ({@code HopperBlockEntity.getContainerAt}: double
 * chests, containers of entities, mod blocks), and honours {@link WorldlyContainer} per side. Fluid storage covers the
 * cauldrons.
 */
final class Storages {

    private static final ResourceId EMPTY_FLUID = ResourceId.minecraft("empty");
    private static final ResourceId WATER = ResourceId.minecraft("water");
    private static final ResourceId LAVA = ResourceId.minecraft("lava");
    private static final ResourceId POWDER_SNOW = ResourceId.minecraft("powder_snow");

    private Storages() {}

    static Optional<ItemStorage> item(Level level, BlockPos pos, Direction side) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return Optional.empty();
        }
        Container container = HopperBlockEntity.getContainerAt(level, pos);
        return container == null ? Optional.empty() : Optional.of(new ContainerStorage(container, side));
    }

    static Optional<FluidStorage> fluid(Level level, BlockPos pos) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.CAULDRON)
                        || state.is(Blocks.WATER_CAULDRON)
                        || state.is(Blocks.POWDER_SNOW_CAULDRON)
                        || state.is(Blocks.LAVA_CAULDRON)
                ? Optional.of(new CauldronStorage(level, pos))
                : Optional.empty();
    }

    /** Two stacks of the same item with no data apart (an item with a name, enchantments, or damage differs). */
    private static boolean isPlain(ItemStack stack, ItemStack probe) {
        return ItemStack.isSameItemSameTags(stack, probe);
    }

    private static final class ContainerStorage implements ItemStorage {

        private final Container container;
        private final WorldlyContainer worldly;
        private final Direction side;
        private final int[] slots;

        ContainerStorage(Container container, Direction side) {
            this.container = container;
            this.worldly = container instanceof WorldlyContainer sided ? sided : null;
            this.side = side;
            this.slots = worldly != null
                    ? worldly.getSlotsForFace(side)
                    : IntStream.range(0, container.getContainerSize()).toArray();
        }

        @Override
        public int slotCount() {
            return slots.length;
        }

        @Override
        public ItemStackView stackAt(int slot) {
            if (slot < 0 || slot >= slots.length) {
                throw new IndexOutOfBoundsException("Slot " + slot + " of " + slots.length);
            }
            ItemStack stack = container.getItem(slots[slot]);
            return stack.isEmpty()
                    ? ItemStackView.EMPTY
                    : new ItemStackView(Views.id(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount());
        }

        private boolean accepts(int slot, ItemStack probe) {
            return container.canPlaceItem(slot, probe)
                    && (worldly == null || worldly.canPlaceItemThroughFace(slot, probe, side));
        }

        @Override
        public int insert(ResourceId item, int count, boolean simulate) {
            Item type = Views.item(item);
            if (count < 1) {
                throw new IllegalArgumentException("count must be at least 1: " + count);
            }
            ItemStack probe = new ItemStack(type);
            int max = Math.min(container.getMaxStackSize(), probe.getMaxStackSize());
            int left = count;
            for (int slot : slots) {
                ItemStack stack = container.getItem(slot);
                if (left > 0 && !stack.isEmpty() && isPlain(stack, probe) && accepts(slot, probe)) {
                    int moved = Math.min(max - stack.getCount(), left);
                    if (moved > 0) {
                        if (!simulate) {
                            stack.grow(moved);
                        }
                        left -= moved;
                    }
                }
            }
            for (int slot : slots) {
                if (left > 0 && container.getItem(slot).isEmpty() && accepts(slot, probe)) {
                    int moved = Math.min(max, left);
                    if (!simulate) {
                        container.setItem(slot, new ItemStack(type, moved));
                    }
                    left -= moved;
                }
            }
            if (!simulate && left < count) {
                container.setChanged();
            }
            return count - left;
        }

        @Override
        public int extract(ResourceId item, int count, boolean simulate) {
            ItemStack probe = new ItemStack(Views.item(item));
            if (count < 1) {
                throw new IllegalArgumentException("count must be at least 1: " + count);
            }
            int left = count;
            for (int slot : slots) {
                ItemStack stack = container.getItem(slot);
                if (left > 0
                        && !stack.isEmpty()
                        && isPlain(stack, probe)
                        && (worldly == null || worldly.canTakeItemThroughFace(slot, stack, side))) {
                    int moved = Math.min(stack.getCount(), left);
                    if (!simulate) {
                        container.removeItem(slot, moved);
                    }
                    left -= moved;
                }
            }
            return count - left;
        }
    }

    private record CauldronStorage(Level level, BlockPos pos) implements FluidStorage {

        @Override
        public ResourceId fluid() {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.WATER_CAULDRON)) {
                return WATER;
            }
            if (state.is(Blocks.POWDER_SNOW_CAULDRON)) {
                return POWDER_SNOW;
            }
            return state.is(Blocks.LAVA_CAULDRON) ? LAVA : EMPTY_FLUID;
        }

        @Override
        public long amount() {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.LAVA_CAULDRON)) {
                return DROPLETS_PER_BUCKET;
            }
            return state.hasProperty(LayeredCauldronBlock.LEVEL)
                    ? state.getValue(LayeredCauldronBlock.LEVEL) * DROPLETS_PER_LEVEL
                    : 0;
        }

        @Override
        public long capacity() {
            return DROPLETS_PER_BUCKET;
        }

        @Override
        public long insert(ResourceId fluid, long amount, boolean simulate) {
            if (amount < 1) {
                throw new IllegalArgumentException("amount must be at least 1: " + amount);
            }
            ResourceId current = fluid();
            if (fluid.equals(LAVA)) {
                if (!current.equals(EMPTY_FLUID) || amount < DROPLETS_PER_BUCKET) {
                    return 0;
                }
                if (!simulate) {
                    level.setBlockAndUpdate(pos, Blocks.LAVA_CAULDRON.defaultBlockState());
                }
                return DROPLETS_PER_BUCKET;
            }
            Block target = fluid.equals(WATER)
                    ? Blocks.WATER_CAULDRON
                    : fluid.equals(POWDER_SNOW) ? Blocks.POWDER_SNOW_CAULDRON : null;
            if (target == null || !(current.equals(EMPTY_FLUID) || current.equals(fluid))) {
                return 0;
            }
            int have = (int) (amount() / DROPLETS_PER_LEVEL);
            int moved = (int) Math.min(LayeredCauldronBlock.MAX_FILL_LEVEL - have, amount / DROPLETS_PER_LEVEL);
            if (moved < 1) {
                return 0;
            }
            if (!simulate) {
                level.setBlockAndUpdate(
                        pos, target.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, have + moved));
            }
            return moved * DROPLETS_PER_LEVEL;
        }

        @Override
        public long extract(ResourceId fluid, long amount, boolean simulate) {
            if (amount < 1) {
                throw new IllegalArgumentException("amount must be at least 1: " + amount);
            }
            ResourceId current = fluid();
            if (current.equals(EMPTY_FLUID) || !current.equals(fluid)) {
                return 0;
            }
            if (current.equals(LAVA)) {
                if (amount < DROPLETS_PER_BUCKET) {
                    return 0;
                }
                if (!simulate) {
                    level.setBlockAndUpdate(pos, Blocks.CAULDRON.defaultBlockState());
                }
                return DROPLETS_PER_BUCKET;
            }
            int have = (int) (amount() / DROPLETS_PER_LEVEL);
            int moved = (int) Math.min(have, amount / DROPLETS_PER_LEVEL);
            if (moved < 1) {
                return 0;
            }
            if (!simulate) {
                BlockState state = level.getBlockState(pos);
                level.setBlockAndUpdate(
                        pos,
                        moved == have
                                ? Blocks.CAULDRON.defaultBlockState()
                                : state.setValue(LayeredCauldronBlock.LEVEL, have - moved));
            }
            return moved * DROPLETS_PER_LEVEL;
        }
    }
}
