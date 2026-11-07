package dev.plexusmodloader.api.inventory;

import dev.plexusmodloader.api.content.item.ItemComponentKey;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A live item stack in a slot or a hand: changes apply to the game's stack directly. Valid during
 * the callback that handed it out; same thread rules as {@link dev.plexusmodloader.api.world.WorldView}.
 * Change stacks on the server; client changes are overwritten by the server's copy.
 */
@ApiStability(StabilityLevel.PREVIEW)
public interface ItemStackRef {

    /** The item, {@code minecraft:air} when empty. */
    ResourceId item();

    /** How many, 0 when empty. */
    int count();

    /** Whether the stack is empty. */
    default boolean isEmpty() {
        return count() == 0;
    }

    /**
     * Whether the stack's item is in an item tag, for example {@code #minecraft:logs}; {@code false}
     * for an empty stack.
     *
     * @throws IllegalArgumentException when {@code tag} is not an item tag
     */
    boolean isIn(dev.plexusmodloader.api.tag.TagKey tag);

    /**
     * Changes the count; 0 empties the stack.
     *
     * @throws IllegalArgumentException for a negative count or one above the stack size
     */
    void setCount(int count);

    /** The component's value, or empty when the stack does not have it. */
    <T> Optional<T> get(ItemComponentKey<T> key);

    /**
     * Sets the component's value.
     *
     * @throws IllegalArgumentException for a key that is not registered
     * @throws IllegalStateException on an empty stack
     */
    <T> void set(ItemComponentKey<T> key, T value);

    /** Removes the component. */
    void remove(ItemComponentKey<?> key);

    /** The name given with an anvil or {@link #setCustomName}, if any. */
    Optional<String> customName();

    /** Gives the stack a name, or removes it with {@code null}. */
    void setCustomName(String name);

    /** Durability used up, 0 for undamaged or undamageable items. */
    int damage();

    /** Durability, 0 for undamageable items. */
    int maxDamage();

    /** Sets the used durability, clamped to {@code [0, maxDamage]}. */
    void setDamage(int damage);

    /**
     * The enchantments on the stack (since 2.1.0), by enchantment ID, with their levels; empty for an empty or
     * unenchanted stack. Enchanted books keep theirs apart in the game and are not included.
     */
    Map<ResourceId, Integer> enchantments();

    /**
     * Sets one enchantment's level, adding the enchantment or changing its level; the game does not check that the item
     * can take it, so a sword can get Efficiency, as with {@code /enchant} in creative. Level 0 removes it.
     *
     * @param level 0 to 255
     * @throws IllegalArgumentException for an enchantment that is not registered (from 1.21: not in the server's
     *     enchantment registry) or a level outside 0 to 255
     * @throws IllegalStateException on an empty stack, or (from 1.21) when no server has started yet
     */
    void setEnchantment(ResourceId enchantment, int level);

    /** The lore lines under the item's name as plain text (formatting is dropped); empty when there are none. */
    List<String> lore();

    /**
     * Replaces the lore lines. Each line is plain text; an empty list removes the lore.
     *
     * @throws IllegalStateException on an empty stack
     */
    void setLore(List<String> lines);

    /** Whether the stack does not lose durability. */
    boolean unbreakable();

    /**
     * Makes the stack unbreakable or not (the tooltip line is shown as the game shows it by default).
     *
     * @throws IllegalStateException on an empty stack
     */
    void setUnbreakable(boolean unbreakable);

    /**
     * The custom model data number resource packs use to pick a model, or empty. From 1.21.4 the game keeps a list of
     * numbers; this is the first one.
     */
    OptionalInt customModelData();

    /**
     * Sets the custom model data number, or removes it with {@code null}. From 1.21.4 it becomes the first and only
     * number of the game's list.
     *
     * @throws IllegalStateException on an empty stack
     */
    void setCustomModelData(Integer value);

    /** A snapshot of item and count. */
    ItemStackView snapshot();
}
