package dev.plexusmodloader.api.transfer;

import dev.plexusmodloader.api.inventory.ItemStackView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/**
 * The item slots of a block that holds items (chest, barrel, furnace, hopper, dropper, brewing stand, shulker box, and
 * blocks of mods built on the game's containers), seen from one side. Get it from {@link
 * dev.plexusmodloader.api.world.WorldView#itemStorage}.
 *
 * <p>It follows the rules hoppers follow: a double chest is one storage, and a furnace offers a different set of slots
 * to each side and accepts only what each slot takes (ore on top, fuel at the sides, results at the bottom).
 *
 * <p><b>Plain items only.</b> {@link #insert} and {@link #extract} move items that carry no extra data (no custom
 * name, enchantments, damage, or other item components) and leave every other stack where it is, so a transfer never
 * silently drops data. {@link #stackAt} shows every stack as it is.
 *
 * <p><b>Thread:</b> the server thread. The storage reads the live block: ask again after the block changed.
 */
@ApiStability(StabilityLevel.PREVIEW)
public interface ItemStorage {

    /** The number of slots this storage offers to its side. */
    int slotCount();

    /**
     * The stack in a slot, with its item and count; an empty slot is {@code minecraft:air} with count 0.
     *
     * @param slot a slot from 0 to {@code slotCount() - 1}
     * @throws IndexOutOfBoundsException when the slot does not exist
     */
    ItemStackView stackAt(int slot);

    /**
     * Puts plain items into the slots, topping up existing stacks of the item first and then using empty slots.
     *
     * @param item the item, which must be registered
     * @param count how many to insert (at least 1)
     * @param simulate {@code true} to only report how many would fit, changing nothing
     * @return how many items were inserted (0 when nothing fits or the side accepts none)
     * @throws IllegalArgumentException when the item is not registered or {@code count} is below 1
     */
    int insert(ResourceId item, int count, boolean simulate);

    /**
     * Takes plain items out of the slots this side may take from, from the first slot on.
     *
     * @param item the item, which must be registered
     * @param count how many to extract (at least 1)
     * @param simulate {@code true} to only report how many could be taken, changing nothing
     * @return how many items were extracted (0 when there are none this side may take)
     * @throws IllegalArgumentException when the item is not registered or {@code count} is below 1
     */
    int extract(ResourceId item, int count, boolean simulate);
}
