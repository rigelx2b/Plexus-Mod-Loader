package dev.plexusmodloader.api.content.item;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;

/**
 * An armour item: one piece of a suit made of a material.
 *
 * @param piece which part of the suit
 * @param material what the suit is made of
 */
@ApiStability(StabilityLevel.PREVIEW)
public record ArmorDefinition(ArmorPiece piece, ArmorMaterialDefinition material) {

    public ArmorDefinition {
        Objects.requireNonNull(piece, "piece");
        Objects.requireNonNull(material, "material");
    }
}
