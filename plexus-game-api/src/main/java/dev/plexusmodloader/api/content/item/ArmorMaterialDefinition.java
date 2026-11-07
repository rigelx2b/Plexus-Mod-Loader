package dev.plexusmodloader.api.content.item;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;
import java.util.Optional;

/**
 * What a suit of armour is made of. One definition is shared by the pieces of a mod ({@link
 * ItemDefinition.Builder#armor}). The game types behind this differ by version (an interface before 1.21,
 * a registered record in 1.21.1, an equipment-asset record from 1.21.11); Plexus Mod Loader builds the right one.
 *
 * <p><b>Textures</b> (the mod jar supplies them; the name is {@code assetId}):
 * <ul>
 *   <li>1.20.1 and 1.20.4: the game only reads the {@code minecraft} namespace for armour, so the files are {@code
 *       assets/minecraft/textures/models/armor/<namespace>_<path>_layer_1.png} (head, chest, feet) and {@code
 *       ..._layer_2.png} (legs).
 *   <li>1.21.1: {@code assets/<namespace>/textures/models/armor/<path>_layer_1.png} and {@code _layer_2.png}.
 *   <li>1.21.11 and later: {@code assets/<namespace>/equipment/<path>.json} naming the textures {@code
 *       assets/<namespace>/textures/entity/equipment/humanoid/<path>.png} and {@code humanoid_leggings/<path>.png}.
 * </ul>
 *
 * @param assetId names the textures, for example {@code my_mod:ruby}
 * @param durabilityMultiplier piece durability is this times the piece's base (helmet 11, chestplate 16,
 *     leggings 15, boots 13); iron 15, diamond 33, netherite 37
 * @param helmetDefense armour points of the helmet
 * @param chestplateDefense armour points of the chestplate
 * @param leggingsDefense armour points of the leggings
 * @param bootsDefense armour points of the boots
 * @param enchantability how readily it gets good enchantments
 * @param toughness reduces strong hits more (diamond 2, netherite 3)
 * @param knockbackResistance 0 to 1 per piece; 0.1 per piece is netherite
 * @param equipSound sound when put on
 * @param repairItem the item that repairs a piece at an anvil, or empty for none; vanilla or mod item
 */
@ApiStability(StabilityLevel.PREVIEW)
public record ArmorMaterialDefinition(
        ResourceId assetId,
        int durabilityMultiplier,
        int helmetDefense,
        int chestplateDefense,
        int leggingsDefense,
        int bootsDefense,
        int enchantability,
        float toughness,
        float knockbackResistance,
        ArmorEquipSound equipSound,
        Optional<ResourceId> repairItem) {

    public ArmorMaterialDefinition {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(equipSound, "equipSound");
        Objects.requireNonNull(repairItem, "repairItem");
        if (durabilityMultiplier < 1) {
            throw new IllegalArgumentException("durabilityMultiplier must be >= 1: " + durabilityMultiplier);
        }
        if (helmetDefense < 0 || chestplateDefense < 0 || leggingsDefense < 0 || bootsDefense < 0) {
            throw new IllegalArgumentException("defense must be >= 0");
        }
        if (enchantability < 0) {
            throw new IllegalArgumentException("enchantability must be >= 0: " + enchantability);
        }
        if (toughness < 0) {
            throw new IllegalArgumentException("toughness must be >= 0: " + toughness);
        }
        if (knockbackResistance < 0 || knockbackResistance > 1) {
            throw new IllegalArgumentException("knockbackResistance must be 0-1: " + knockbackResistance);
        }
    }

    /** A material with no toughness, no knockback resistance, the generic equip sound, and no repair item. */
    public static ArmorMaterialDefinition of(
            ResourceId assetId,
            int durabilityMultiplier,
            int helmetDefense,
            int chestplateDefense,
            int leggingsDefense,
            int bootsDefense,
            int enchantability) {
        return new ArmorMaterialDefinition(
                assetId,
                durabilityMultiplier,
                helmetDefense,
                chestplateDefense,
                leggingsDefense,
                bootsDefense,
                enchantability,
                0.0f,
                0.0f,
                ArmorEquipSound.GENERIC,
                Optional.empty());
    }

    /** A copy that an anvil repairs with {@code item}. */
    public ArmorMaterialDefinition repairedWith(ResourceId item) {
        return new ArmorMaterialDefinition(
                assetId,
                durabilityMultiplier,
                helmetDefense,
                chestplateDefense,
                leggingsDefense,
                bootsDefense,
                enchantability,
                toughness,
                knockbackResistance,
                equipSound,
                Optional.of(item));
    }

    /** A copy with the given equip sound. */
    public ArmorMaterialDefinition withEquipSound(ArmorEquipSound sound) {
        return new ArmorMaterialDefinition(
                assetId,
                durabilityMultiplier,
                helmetDefense,
                chestplateDefense,
                leggingsDefense,
                bootsDefense,
                enchantability,
                toughness,
                knockbackResistance,
                sound,
                repairItem);
    }

    /** A copy with toughness and knockback resistance. */
    public ArmorMaterialDefinition withToughness(float toughness, float knockbackResistance) {
        return new ArmorMaterialDefinition(
                assetId,
                durabilityMultiplier,
                helmetDefense,
                chestplateDefense,
                leggingsDefense,
                bootsDefense,
                enchantability,
                toughness,
                knockbackResistance,
                equipSound,
                repairItem);
    }

    /** The armour points of {@code piece}. */
    public int defense(ArmorPiece piece) {
        return switch (piece) {
            case HELMET -> helmetDefense;
            case CHESTPLATE -> chestplateDefense;
            case LEGGINGS -> leggingsDefense;
            case BOOTS -> bootsDefense;
        };
    }

    /** The durability of {@code piece}: the piece's base times {@link #durabilityMultiplier()}. */
    public int durability(ArmorPiece piece) {
        return piece.baseDurability() * durabilityMultiplier;
    }
}
