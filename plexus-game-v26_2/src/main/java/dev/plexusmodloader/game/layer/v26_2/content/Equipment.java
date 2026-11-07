package dev.plexusmodloader.game.layer.v26_2.content;

import dev.plexusmodloader.api.content.item.ArmorDefinition;
import dev.plexusmodloader.api.content.item.ArmorMaterialDefinition;
import dev.plexusmodloader.api.content.item.ArmorPiece;
import dev.plexusmodloader.api.content.item.ItemDefinition;
import dev.plexusmodloader.api.content.item.ToolDefinition;
import dev.plexusmodloader.api.content.item.ToolMaterialDefinition;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.game.layer.v26_2.Views;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;

/**
 * Builds tool and armour items from {@link ItemDefinition#tool()} and {@link ItemDefinition#armor()} (ADR-0040).
 * From 1.21.11 the game describes tools by a {@code ToolMaterial} record and armour by an {@code ArmorMaterial}
 * record that names an equipment asset; both fill the item's components through {@code Item.Properties}. Repair
 * items are tags in these versions: Plexus Mod Loader writes the tag {@link #repairTagId(ResourceId)} (see {@code
 * GeneratedDataPack}).
 */
public final class Equipment {

    /** The namespace and directory of the generated item tags that hold one repair item each. */
    private static final String REPAIR_NAMESPACE = "plexus";

    private Equipment() {}

    /** Whether {@code definition} is a tool or armour piece, which this class builds. */
    static boolean handles(ItemDefinition definition) {
        return definition.tool().isPresent() || definition.armor().isPresent();
    }

    /** The ID of the generated item tag that lists {@code repairItem} alone. */
    public static ResourceId repairTagId(ResourceId repairItem) {
        return ResourceId.of(REPAIR_NAMESPACE, "repairable_by/" + repairItem.namespace() + "/" + repairItem.path());
    }

    /** The item for a tool or armour definition; {@code properties} already carry the durability and ID. */
    static Item create(Item.Properties properties, ItemDefinition definition) {
        Optional<ToolDefinition> tool = definition.tool();
        if (tool.isPresent()) {
            return new Item(tool(properties, tool.get()));
        }
        ArmorDefinition armor = definition.armor().orElseThrow();
        ArmorType type = type(armor.piece());
        return new Item(properties.humanoidArmor(armorMaterial(armor.material()), type));
    }

    private static Item.Properties tool(Item.Properties properties, ToolDefinition tool) {
        ToolMaterialDefinition definition = tool.material();
        ToolMaterial material = new ToolMaterial(
                TagKey.create(
                        Registries.BLOCK,
                        Views.id(ResourceId.minecraft(
                                "incorrect_for_" + definition.miningTier().vanillaName() + "_tool"))),
                definition.durability(),
                definition.miningSpeed(),
                definition.attackDamageBonus(),
                definition.enchantability(),
                repairTag(definition.repairItem()));
        float damage = tool.attackDamage();
        float speed = tool.attackSpeed();
        return switch (tool.kind()) {
            case SWORD -> properties.sword(material, damage, speed);
            case PICKAXE -> properties.pickaxe(material, damage, speed);
            case AXE -> properties.axe(material, damage, speed);
            case SHOVEL -> properties.shovel(material, damage, speed);
            case HOE -> properties.hoe(material, damage, speed);
        };
    }

    private static ArmorType type(ArmorPiece piece) {
        return switch (piece) {
            case HELMET -> ArmorType.HELMET;
            case CHESTPLATE -> ArmorType.CHESTPLATE;
            case LEGGINGS -> ArmorType.LEGGINGS;
            case BOOTS -> ArmorType.BOOTS;
        };
    }

    private static ArmorMaterial armorMaterial(ArmorMaterialDefinition definition) {
        Map<ArmorType, Integer> defense = new EnumMap<>(ArmorType.class);
        defense.put(ArmorType.HELMET, definition.helmetDefense());
        defense.put(ArmorType.CHESTPLATE, definition.chestplateDefense());
        defense.put(ArmorType.LEGGINGS, definition.leggingsDefense());
        defense.put(ArmorType.BOOTS, definition.bootsDefense());
        // Animal armour is not offered by the Native API; the game still looks the value up for every type.
        defense.put(ArmorType.BODY, definition.chestplateDefense());
        ResourceKey<EquipmentAsset> asset = ResourceKey.create(EquipmentAssets.ROOT_ID, Views.id(definition.assetId()));
        return new ArmorMaterial(
                definition.durabilityMultiplier(),
                defense,
                definition.enchantability(),
                equipSound(definition),
                definition.toughness(),
                definition.knockbackResistance(),
                repairTag(definition.repairItem()),
                asset);
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

    /**
     * The game wants a tag of repair items. The generated tag lists the repair item; without one the tag
     * {@code plexus:repairable_by/none} exists nowhere, so nothing repairs the item.
     */
    private static TagKey<Item> repairTag(Optional<ResourceId> repairItem) {
        ResourceId id =
                repairItem.map(Equipment::repairTagId).orElse(ResourceId.of(REPAIR_NAMESPACE, "repairable_by/none"));
        return TagKey.create(Registries.ITEM, Views.id(id));
    }
}
