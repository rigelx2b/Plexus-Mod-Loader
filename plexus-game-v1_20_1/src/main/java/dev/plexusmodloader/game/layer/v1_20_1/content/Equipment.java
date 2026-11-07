package dev.plexusmodloader.game.layer.v1_20_1.content;

import dev.plexusmodloader.api.content.item.ArmorDefinition;
import dev.plexusmodloader.api.content.item.ArmorMaterialDefinition;
import dev.plexusmodloader.api.content.item.ArmorPiece;
import dev.plexusmodloader.api.content.item.ItemDefinition;
import dev.plexusmodloader.api.content.item.ToolDefinition;
import dev.plexusmodloader.api.content.item.ToolMaterialDefinition;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.game.layer.v1_20_1.Views;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Builds tool and armour items from {@link ItemDefinition#tool()} and {@link ItemDefinition#armor()} (ADR-0040).
 * Before 1.21 the game's {@code Tier} and {@code ArmorMaterial} are interfaces and the tool classes take the
 * tier in their constructor, so a tier and a material are implemented here; the attack damage of swords,
 * pickaxes, and hoes is a whole number in these versions and is rounded.
 */
final class Equipment {

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
        return new ArmorItem(new PlexusArmorMaterial(armor.material()), type(armor.piece()), properties);
    }

    private static Item tool(Item.Properties properties, ToolDefinition tool) {
        Tier tier = new PlexusTier(tool.material());
        int wholeDamage = Math.round(tool.attackDamage());
        float speed = tool.attackSpeed();
        return switch (tool.kind()) {
            case SWORD -> new SwordItem(tier, wholeDamage, speed, properties);
            case PICKAXE -> new PlexusPickaxe(tier, wholeDamage, speed, properties);
            case AXE -> new PlexusAxe(tier, tool.attackDamage(), speed, properties);
            case SHOVEL -> new ShovelItem(tier, tool.attackDamage(), speed, properties);
            case HOE -> new PlexusHoe(tier, wholeDamage, speed, properties);
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

    private static ArmorPiece piece(ArmorItem.Type type) {
        return switch (type) {
            case HELMET -> ArmorPiece.HELMET;
            case CHESTPLATE -> ArmorPiece.CHESTPLATE;
            case LEGGINGS -> ArmorPiece.LEGGINGS;
            case BOOTS -> ArmorPiece.BOOTS;
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
        public int getLevel() {
            return material.miningTier().level();
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

    /** The game's {@code ArmorMaterial} for an armour material. */
    private record PlexusArmorMaterial(ArmorMaterialDefinition material) implements ArmorMaterial {

        @Override
        public int getDurabilityForType(ArmorItem.Type type) {
            return material.durability(piece(type));
        }

        @Override
        public int getDefenseForType(ArmorItem.Type type) {
            return material.defense(piece(type));
        }

        @Override
        public int getEnchantmentValue() {
            return material.enchantability();
        }

        @Override
        public SoundEvent getEquipSound() {
            return switch (material.equipSound()) {
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

        @Override
        public Ingredient getRepairIngredient() {
            return ingredient(material.repairItem());
        }

        /**
         * The game builds the texture path as {@code textures/models/armor/<name>_layer_1.png} in the {@code
         * minecraft} namespace, so a mod id cannot be part of a namespace here: {@code my_mod:ruby} is {@code my_mod_ruby}.
         */
        @Override
        public String getName() {
            ResourceId id = material.assetId();
            return id.namespace().equals("minecraft") ? id.path() : id.namespace() + "_" + id.path();
        }

        @Override
        public float getToughness() {
            return material.toughness();
        }

        @Override
        public float getKnockbackResistance() {
            return material.knockbackResistance();
        }
    }

    // The constructors of these tool classes are protected in the game; a subclass reaches them.

    private static final class PlexusPickaxe extends PickaxeItem {
        PlexusPickaxe(Tier tier, int damage, float speed, Item.Properties properties) {
            super(tier, damage, speed, properties);
        }
    }

    private static final class PlexusAxe extends AxeItem {
        PlexusAxe(Tier tier, float damage, float speed, Item.Properties properties) {
            super(tier, damage, speed, properties);
        }
    }

    private static final class PlexusHoe extends HoeItem {
        PlexusHoe(Tier tier, int damage, float speed, Item.Properties properties) {
            super(tier, damage, speed, properties);
        }
    }
}
