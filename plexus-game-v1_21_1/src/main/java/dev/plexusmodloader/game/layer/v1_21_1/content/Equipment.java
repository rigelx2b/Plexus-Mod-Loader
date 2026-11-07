package dev.plexusmodloader.game.layer.v1_21_1.content;

import dev.plexusmodloader.api.content.item.ArmorDefinition;
import dev.plexusmodloader.api.content.item.ArmorMaterialDefinition;
import dev.plexusmodloader.api.content.item.ArmorPiece;
import dev.plexusmodloader.api.content.item.ItemDefinition;
import dev.plexusmodloader.api.content.item.ToolDefinition;
import dev.plexusmodloader.api.content.item.ToolMaterialDefinition;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.game.layer.v1_21_1.Views;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;

/**
 * Builds tool and armour items from {@link ItemDefinition#tool()} and {@link ItemDefinition#armor()} (ADR-0040).
 * In 1.21.1 a tool item takes a {@code Tier} and carries its attack values as item attributes, and armour
 * takes a {@code ArmorMaterial} that must be registered; the attack damage of swords is a whole number and is
 * rounded.
 */
final class Equipment {

    private static final Map<ResourceId, Holder<ArmorMaterial>> ARMOR_MATERIALS = new HashMap<>();

    private Equipment() {}

    /** Whether {@code definition} is a tool or armour piece, which this class builds. */
    static boolean handles(ItemDefinition definition) {
        return definition.tool().isPresent() || definition.armor().isPresent();
    }

    /** The item for a tool or armour definition; {@code properties} already carry the durability. */
    static Item create(Item.Properties properties, ItemDefinition definition) {
        Optional<ToolDefinition> tool = definition.tool();
        if (tool.isPresent()) {
            return tool(properties, tool.get());
        }
        ArmorDefinition armor = definition.armor().orElseThrow();
        return new ArmorItem(armorMaterial(armor.material()), type(armor.piece()), properties);
    }

    private static Item tool(Item.Properties properties, ToolDefinition tool) {
        Tier tier = new PlexusTier(tool.material());
        float damage = tool.attackDamage();
        float speed = tool.attackSpeed();
        return switch (tool.kind()) {
            case SWORD ->
                new SwordItem(tier, properties.attributes(SwordItem.createAttributes(tier, Math.round(damage), speed)));
            case PICKAXE ->
                new PickaxeItem(tier, properties.attributes(DiggerItem.createAttributes(tier, damage, speed)));
            case AXE -> new AxeItem(tier, properties.attributes(DiggerItem.createAttributes(tier, damage, speed)));
            case SHOVEL ->
                new ShovelItem(tier, properties.attributes(DiggerItem.createAttributes(tier, damage, speed)));
            case HOE -> new HoeItem(tier, properties.attributes(DiggerItem.createAttributes(tier, damage, speed)));
        };
    }

    private static ArmorItem.Type type(ArmorPiece piece) {
        return switch (piece) {
            case HELMET -> ArmorItem.Type.HELMET;
            case CHESTPLATE -> ArmorItem.Type.CHESTPLATE;
            case LEGGINGS -> ArmorItem.Type.LEGGINGS;
            case BOOTS -> ArmorItem.Type.BOOTS;
        };
    }

    /** Registers the material once, however many pieces use it (the game registers materials by ID). */
    private static Holder<ArmorMaterial> armorMaterial(ArmorMaterialDefinition definition) {
        return ARMOR_MATERIALS.computeIfAbsent(definition.assetId(), id -> {
            Map<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
            defense.put(ArmorItem.Type.HELMET, definition.helmetDefense());
            defense.put(ArmorItem.Type.CHESTPLATE, definition.chestplateDefense());
            defense.put(ArmorItem.Type.LEGGINGS, definition.leggingsDefense());
            defense.put(ArmorItem.Type.BOOTS, definition.bootsDefense());
            // Animal armour is not offered by the Native API; the game still looks the value up for every type.
            defense.put(ArmorItem.Type.BODY, definition.chestplateDefense());
            ArmorMaterial material = new ArmorMaterial(
                    defense,
                    definition.enchantability(),
                    equipSound(definition),
                    () -> ingredient(definition.repairItem()),
                    List.of(new ArmorMaterial.Layer(Views.id(id))),
                    definition.toughness(),
                    definition.knockbackResistance());
            return Registry.registerForHolder(BuiltInRegistries.ARMOR_MATERIAL, Views.id(id), material);
        });
    }

    private static Holder<SoundEvent> equipSound(ArmorMaterialDefinition definition) {
        return switch (definition.equipSound()) {
            case GENERIC -> SoundEvents.ARMOR_EQUIP_GENERIC;
            case LEATHER -> SoundEvents.ARMOR_EQUIP_LEATHER;
            case CHAIN -> SoundEvents.ARMOR_EQUIP_CHAIN;
            case IRON -> SoundEvents.ARMOR_EQUIP_IRON;
            case GOLD -> SoundEvents.ARMOR_EQUIP_GOLD;
            case DIAMOND -> SoundEvents.ARMOR_EQUIP_DIAMOND;
            case TURTLE -> SoundEvents.ARMOR_EQUIP_TURTLE;
            case NETHERITE -> SoundEvents.ARMOR_EQUIP_NETHERITE;
        };
    }

    /** The repair ingredient for an item that may be registered later: looked up when asked. */
    private static Ingredient ingredient(Optional<ResourceId> repairItem) {
        return repairItem
                .flatMap(id -> BuiltInRegistries.ITEM.getOptional(Views.id(id)))
                .map(item -> Ingredient.of(item))
                .orElse(Ingredient.EMPTY);
    }

    /** The game's {@code Tier} for a tool material. */
    private record PlexusTier(ToolMaterialDefinition material) implements Tier {

        @Override
        public int getUses() {
            return material.durability();
        }

        @Override
        public float getSpeed() {
            return material.miningSpeed();
        }

        @Override
        public float getAttackDamageBonus() {
            return material.attackDamageBonus();
        }

        @Override
        public TagKey<Block> getIncorrectBlocksForDrops() {
            return TagKey.create(
                    Registries.BLOCK,
                    Views.id(ResourceId.minecraft(
                            "incorrect_for_" + material.miningTier().vanillaName() + "_tool")));
        }

        @Override
        public int getEnchantmentValue() {
            return material.enchantability();
        }

        @Override
        public Ingredient getRepairIngredient() {
            return ingredient(material.repairItem());
        }
    }
}
