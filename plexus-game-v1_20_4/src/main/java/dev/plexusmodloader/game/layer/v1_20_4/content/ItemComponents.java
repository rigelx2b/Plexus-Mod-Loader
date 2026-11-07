package dev.plexusmodloader.game.layer.v1_20_4.content;

import dev.plexusmodloader.api.content.item.ComponentType;
import dev.plexusmodloader.api.content.item.ItemComponentKey;
import dev.plexusmodloader.api.data.DataTree;
import dev.plexusmodloader.api.inventory.ItemStackRef;
import dev.plexusmodloader.api.inventory.ItemStackView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.game.layer.v1_20_4.GameTags;
import dev.plexusmodloader.game.layer.v1_20_4.Views;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Registers mod item components and gives mods live access to item stacks. Minecraft 1.20.4 has no data
 * components, so a component is a tag in the stack's NBT: all of a stack's mod components live in the compound
 * {@value #ROOT}, keyed by the component's ID. The tag travels with the stack to the client and into saved worlds.
 */
public final class ItemComponents {

    /** The stack tag that holds the mod components. */
    public static final String ROOT = "plexus_components";

    private static final Map<ResourceId, ComponentType<?>> TYPES = new ConcurrentHashMap<>();

    private ItemComponents() {}

    /** Records the declared components, so stacks only accept declared keys; called before the registries freeze. */
    public static void install(List<ItemComponentKey<?>> keys) {
        for (ItemComponentKey<?> key : keys) {
            TYPES.put(key.id(), key.type());
        }
    }

    private static void requireRegistered(ItemComponentKey<?> key) {
        ComponentType<?> type = TYPES.get(key.id());
        if (type == null) {
            throw new IllegalArgumentException("Item component " + key.id() + " is not registered");
        }
    }

    private static Tag encode(ComponentType<?> type, Object value) {
        if (type == ComponentType.INTEGER) {
            return IntTag.valueOf((Integer) value);
        }
        if (type == ComponentType.BOOLEAN) {
            return ByteTag.valueOf((Boolean) value);
        }
        if (type == ComponentType.STRING) {
            return StringTag.valueOf((String) value);
        }
        return Nbt.toTag((DataTree) value);
    }

    /** The value stored in {@code tag}, or empty when it is missing or has another type. */
    private static Optional<Object> decode(ComponentType<?> type, Tag tag) {
        if (type == ComponentType.INTEGER && tag instanceof IntTag integer) {
            return Optional.of(integer.getAsInt());
        }
        if (type == ComponentType.BOOLEAN && tag instanceof ByteTag bool) {
            return Optional.of(bool.getAsByte() != 0);
        }
        if (type == ComponentType.STRING && tag instanceof StringTag text) {
            return Optional.of(text.getAsString());
        }
        if (type == ComponentType.TREE && tag instanceof CompoundTag compound) {
            return Optional.of(Nbt.fromTag(compound));
        }
        return Optional.empty();
    }

    /** A live reference to {@code stack}; {@code changed} runs after every change. */
    public static ItemStackRef ref(ItemStack stack, Runnable changed) {
        return new StackRef(stack, changed);
    }

    /** A snapshot of {@code stack}. */
    public static ItemStackView snapshot(ItemStack stack) {
        return stack.isEmpty()
                ? ItemStackView.EMPTY
                : new ItemStackView(Views.id(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount());
    }

    private record StackRef(ItemStack stack, Runnable changed) implements ItemStackRef {

        @Override
        public ResourceId item() {
            return snapshot().item();
        }

        @Override
        public int count() {
            return stack.isEmpty() ? 0 : stack.getCount();
        }

        @Override
        public boolean isIn(dev.plexusmodloader.api.tag.TagKey tag) {
            boolean in = GameTags.isIn(stack.getItem(), tag);
            return in && !stack.isEmpty();
        }

        @Override
        public void setCount(int count) {
            if (count < 0 || count > stack.getMaxStackSize()) {
                throw new IllegalArgumentException("count must be 0-" + stack.getMaxStackSize() + ": " + count);
            }
            stack.setCount(count);
            changed.run();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> get(ItemComponentKey<T> key) {
            requireRegistered(key);
            CompoundTag root = stack.isEmpty() ? null : stack.getTagElement(ROOT);
            if (root == null || !root.contains(key.id().toString())) {
                return Optional.empty();
            }
            return decode(key.type(), root.get(key.id().toString())).map(value -> (T) value);
        }

        @Override
        public <T> void set(ItemComponentKey<T> key, T value) {
            requireRegistered(key);
            requireStack();
            Object checked = key.type().valueClass().cast(value);
            stack.getOrCreateTagElement(ROOT).put(key.id().toString(), encode(key.type(), checked));
            changed.run();
        }

        @Override
        public void remove(ItemComponentKey<?> key) {
            requireRegistered(key);
            CompoundTag root = stack.isEmpty() ? null : stack.getTagElement(ROOT);
            if (root != null) {
                root.remove(key.id().toString());
                if (root.isEmpty()) {
                    stack.removeTagKey(ROOT);
                }
            }
            changed.run();
        }

        @Override
        public Optional<String> customName() {
            return stack.isEmpty() || !stack.hasCustomHoverName()
                    ? Optional.empty()
                    : Optional.of(stack.getHoverName().getString());
        }

        @Override
        public void setCustomName(String name) {
            if (name == null) {
                if (!stack.isEmpty()) {
                    stack.resetHoverName();
                }
            } else {
                requireStack();
                stack.setHoverName(Component.literal(name));
            }
            changed.run();
        }

        @Override
        public int damage() {
            return stack.getDamageValue();
        }

        @Override
        public int maxDamage() {
            return stack.getMaxDamage();
        }

        @Override
        public void setDamage(int damage) {
            stack.setDamageValue(Math.max(0, Math.min(damage, stack.getMaxDamage())));
            changed.run();
        }

        @Override
        public Map<ResourceId, Integer> enchantments() {
            Map<ResourceId, Integer> found = new java.util.LinkedHashMap<>();
            if (!stack.isEmpty()) {
                net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(stack)
                        .forEach((enchantment, level) ->
                                found.put(Views.id(BuiltInRegistries.ENCHANTMENT.getKey(enchantment)), level));
            }
            return found;
        }

        @Override
        public void setEnchantment(ResourceId id, int level) {
            net.minecraft.world.item.enchantment.Enchantment enchantment = BuiltInRegistries.ENCHANTMENT
                    .getOptional(Views.id(id))
                    .orElseThrow(() -> new IllegalArgumentException("Enchantment " + id + " is not registered"));
            if (level < 0 || level > 255) {
                throw new IllegalArgumentException("level must be 0-255: " + level);
            }
            requireStack();
            Map<net.minecraft.world.item.enchantment.Enchantment, Integer> all = new java.util.LinkedHashMap<>(
                    net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(stack));
            if (level == 0) {
                all.remove(enchantment);
            } else {
                all.put(enchantment, level);
            }
            net.minecraft.world.item.enchantment.EnchantmentHelper.setEnchantments(all, stack);
            changed.run();
        }

        @Override
        public List<String> lore() {
            CompoundTag display = stack.isEmpty() ? null : stack.getTagElement("display");
            if (display == null || !display.contains("Lore", Tag.TAG_LIST)) {
                return List.of();
            }
            net.minecraft.nbt.ListTag list = display.getList("Lore", Tag.TAG_STRING);
            List<String> lines = new java.util.ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Component line = Component.Serializer.fromJson(list.getString(i));
                lines.add(line == null ? "" : line.getString());
            }
            return lines;
        }

        @Override
        public void setLore(List<String> lines) {
            requireStack();
            if (lines.isEmpty()) {
                CompoundTag display = stack.getTagElement("display");
                if (display != null) {
                    display.remove("Lore");
                    if (display.isEmpty()) {
                        stack.removeTagKey("display");
                    }
                }
            } else {
                net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
                for (String line : lines) {
                    list.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(line))));
                }
                stack.getOrCreateTagElement("display").put("Lore", list);
            }
            changed.run();
        }

        @Override
        public boolean unbreakable() {
            return !stack.isEmpty() && stack.hasTag() && stack.getTag().getBoolean("Unbreakable");
        }

        @Override
        public void setUnbreakable(boolean unbreakable) {
            requireStack();
            if (unbreakable) {
                stack.getOrCreateTag().putBoolean("Unbreakable", true);
            } else if (stack.hasTag()) {
                stack.removeTagKey("Unbreakable");
            }
            changed.run();
        }

        @Override
        public java.util.OptionalInt customModelData() {
            return !stack.isEmpty() && stack.hasTag() && stack.getTag().contains("CustomModelData", Tag.TAG_ANY_NUMERIC)
                    ? java.util.OptionalInt.of(stack.getTag().getInt("CustomModelData"))
                    : java.util.OptionalInt.empty();
        }

        @Override
        public void setCustomModelData(Integer value) {
            requireStack();
            if (value == null) {
                stack.removeTagKey("CustomModelData");
            } else {
                stack.getOrCreateTag().putInt("CustomModelData", value);
            }
            changed.run();
        }

        @Override
        public ItemStackView snapshot() {
            return ItemComponents.snapshot(stack);
        }

        private void requireStack() {
            if (stack.isEmpty()) {
                throw new IllegalStateException("The stack is empty");
            }
        }
    }
}
