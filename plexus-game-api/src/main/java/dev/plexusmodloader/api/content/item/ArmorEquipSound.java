package dev.plexusmodloader.api.content.item;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** The sound played when armour is put on: one of the vanilla armour sets. */
@ApiStability(StabilityLevel.PREVIEW)
public enum ArmorEquipSound {
    /** {@code item.armor.equip_generic}. */
    GENERIC,
    /** {@code item.armor.equip_leather}. */
    LEATHER,
    /** {@code item.armor.equip_chain}. */
    CHAIN,
    /** {@code item.armor.equip_iron}. */
    IRON,
    /** {@code item.armor.equip_gold}. */
    GOLD,
    /** {@code item.armor.equip_diamond}. */
    DIAMOND,
    /** {@code item.armor.equip_turtle}. */
    TURTLE,
    /** {@code item.armor.equip_netherite}. */
    NETHERITE
}
