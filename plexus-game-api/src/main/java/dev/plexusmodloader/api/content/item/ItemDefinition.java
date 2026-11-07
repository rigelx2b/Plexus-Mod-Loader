package dev.plexusmodloader.api.content.item;

import dev.plexusmodloader.api.content.CreativeTab;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;
import java.util.Optional;

/**
 * Properties of a mod item or block item. Created with {@link #builder()}; immutable.
 *
 * @param maxStackSize 1–99; items with durability always stack to 1
 * @param durability uses before breaking, 0 for none
 * @param fireResistant whether the dropped item survives fire and lava
 * @param rarity name colour
 * @param food what eating it does, or empty when it is not food
 * @param creativeTab creative inventory tab to list it in, or empty
 * @param creativeTabAfter the item to list this one directly after in {@code creativeTab} (a vanilla or mod item),
 *     or empty to list it at the end; an item not found in the tab means the end
 * @param behavior use callbacks; ignored for block items, tools, and armour
 * @param fuelTicks how long the item burns as furnace fuel in ticks (coal 1600, a stick 100), 0 for not a fuel
 * @param compostChance the chance (above 0, up to 1) that a composter turns the item into a layer, or 0 for not compostable
 * @param tool the tool kind and material when this item is a sword, pickaxe, axe, shovel, or hoe, else empty; the
 *     durability is the material's
 * @param armor the armour piece and material when this item is armour, else empty; the durability is the
 *     material's for that piece
 */
@ApiStability(StabilityLevel.PREVIEW)
public record ItemDefinition(
        int maxStackSize,
        int durability,
        boolean fireResistant,
        ItemRarity rarity,
        Optional<FoodDefinition> food,
        Optional<CreativeTab> creativeTab,
        Optional<ResourceId> creativeTabAfter,
        ItemBehavior behavior,
        int fuelTicks,
        float compostChance,
        Optional<ToolDefinition> tool,
        Optional<ArmorDefinition> armor) {

    public ItemDefinition {
        if (maxStackSize < 1 || maxStackSize > 99) {
            throw new IllegalArgumentException("maxStackSize must be 1-99: " + maxStackSize);
        }
        if (durability < 0) {
            throw new IllegalArgumentException("durability must be >= 0: " + durability);
        }
        if (durability > 0 && maxStackSize != 1) {
            throw new IllegalArgumentException("items with durability must have maxStackSize 1");
        }
        Objects.requireNonNull(rarity, "rarity");
        Objects.requireNonNull(food, "food");
        Objects.requireNonNull(creativeTab, "creativeTab");
        Objects.requireNonNull(creativeTabAfter, "creativeTabAfter");
        if (creativeTabAfter.isPresent() && creativeTab.isEmpty()) {
            throw new IllegalArgumentException("creativeTabAfter needs a creativeTab");
        }
        Objects.requireNonNull(behavior, "behavior");
        if (fuelTicks < 0 || fuelTicks > 32767) {
            throw new IllegalArgumentException("fuelTicks must be 0-32767: " + fuelTicks);
        }
        if (compostChance < 0 || compostChance > 1) {
            throw new IllegalArgumentException("compostChance must be 0-1: " + compostChance);
        }
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(armor, "armor");
        if (tool.isPresent() && armor.isPresent()) {
            throw new IllegalArgumentException("an item is a tool or armour, not both");
        }
        if ((tool.isPresent() || armor.isPresent()) && food.isPresent()) {
            throw new IllegalArgumentException("tools and armour cannot be food");
        }
        if (tool.isPresent() && durability != tool.get().material().durability()) {
            throw new IllegalArgumentException("a tool's durability is its material's: " + durability);
        }
        if (armor.isPresent()
                && durability != armor.get().material().durability(armor.get().piece())) {
            throw new IllegalArgumentException("an armour piece's durability is its material's: " + durability);
        }
    }

    /** A builder for a common item stacking to 64, listed in no tab. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds an {@link ItemDefinition}. */
    public static final class Builder {

        private int maxStackSize = 64;
        private int durability;
        private boolean fireResistant;
        private ItemRarity rarity = ItemRarity.COMMON;
        private Optional<FoodDefinition> food = Optional.empty();
        private Optional<CreativeTab> creativeTab = Optional.empty();
        private Optional<ResourceId> creativeTabAfter = Optional.empty();
        private ItemBehavior behavior = new ItemBehavior() {};
        private int fuelTicks;
        private float compostChance;
        private Optional<ToolDefinition> tool = Optional.empty();
        private Optional<ArmorDefinition> armor = Optional.empty();

        private Builder() {}

        /** Sets the maximum stack size, 1–99. */
        public Builder maxStackSize(int maxStackSize) {
            this.maxStackSize = maxStackSize;
            return this;
        }

        /** Gives the item durability; also sets the stack size to 1. */
        public Builder durability(int durability) {
            this.durability = durability;
            this.maxStackSize = 1;
            return this;
        }

        /** The dropped item survives fire and lava. */
        public Builder fireResistant() {
            this.fireResistant = true;
            return this;
        }

        /** Sets the name colour. */
        public Builder rarity(ItemRarity rarity) {
            this.rarity = rarity;
            return this;
        }

        /** Makes the item edible. */
        public Builder food(FoodDefinition food) {
            if (tool.isPresent() || armor.isPresent()) {
                throw new IllegalStateException("an item is a tool, armour, or food");
            }
            this.food = Optional.of(food);
            return this;
        }

        /** Lists the item at the end of a creative inventory tab. */
        public Builder creativeTab(CreativeTab tab) {
            this.creativeTab = Optional.of(tab);
            this.creativeTabAfter = Optional.empty();
            return this;
        }

        /**
         * Lists the item in a creative inventory tab directly after {@code after}, a vanilla or mod item
         * (like Fabric API's {@code ItemGroupEvents.modifyEntriesEvent(..).addAfter} or NeoForge's
         * {@code BuildCreativeModeTabContentsEvent.insertAfter}). When {@code after} is not in the tab the
         * item goes at the end. Several items placed after the same item keep their declaration order.
         */
        public Builder creativeTab(CreativeTab tab, ResourceId after) {
            this.creativeTab = Optional.of(tab);
            this.creativeTabAfter = Optional.of(Objects.requireNonNull(after, "after"));
            return this;
        }

        /**
         * Makes the item a tool of {@code kind} with the vanilla attack values of that kind. Sets the durability
         * and the stack size from the material. The use callbacks of {@link #behavior} are ignored for tools.
         *
         * @throws IllegalStateException when the builder already holds armour or food
         */
        public Builder tool(ToolKind kind, ToolMaterialDefinition material) {
            return tool(ToolDefinition.of(kind, material));
        }

        /** As {@link #tool(ToolKind, ToolMaterialDefinition)}, with attack values of your own. */
        public Builder tool(ToolDefinition tool) {
            if (armor.isPresent() || food.isPresent()) {
                throw new IllegalStateException("an item is a tool, armour, or food");
            }
            this.tool = Optional.of(tool);
            this.durability = tool.material().durability();
            this.maxStackSize = 1;
            return this;
        }

        /**
         * Makes the item a piece of armour. Sets the durability and the stack size from the material. The
         * use callbacks of {@link #behavior} are ignored for armour.
         *
         * @throws IllegalStateException when the builder already holds a tool or food
         */
        public Builder armor(ArmorPiece piece, ArmorMaterialDefinition material) {
            if (tool.isPresent() || food.isPresent()) {
                throw new IllegalStateException("an item is a tool, armour, or food");
            }
            this.armor = Optional.of(new ArmorDefinition(piece, material));
            this.durability = material.durability(piece);
            this.maxStackSize = 1;
            return this;
        }

        /**
         * Makes the item a furnace fuel that burns for {@code ticks} (coal 1600, a blaze rod 2400, lava bucket 20000;
         * at most 32767). Applies to smelters that use the vanilla fuel rules (furnace, smoker, blast furnace). Like
         * Fabric API's {@code FuelRegistry} and NeoForge's {@code burn_time}, but for the mod's own items only.
         */
        public Builder fuel(int ticks) {
            this.fuelTicks = ticks;
            return this;
        }

        /**
         * Makes the item compostable: a composter turns it into a layer with probability {@code chance} (above 0,
         * up to 1; an empty composter always takes the first item). Like Fabric API's {@code
         * CompostingChanceRegistry}, but for the mod's own items only.
         */
        public Builder compostable(float chance) {
            this.compostChance = chance;
            return this;
        }

        /** Sets the use callbacks. */
        public Builder behavior(ItemBehavior behavior) {
            this.behavior = behavior;
            return this;
        }

        /** Creates the definition; invalid values throw {@link IllegalArgumentException}. */
        public ItemDefinition build() {
            return new ItemDefinition(
                    maxStackSize,
                    durability,
                    fireResistant,
                    rarity,
                    food,
                    creativeTab,
                    creativeTabAfter,
                    behavior,
                    fuelTicks,
                    compostChance,
                    tool,
                    armor);
        }
    }
}
