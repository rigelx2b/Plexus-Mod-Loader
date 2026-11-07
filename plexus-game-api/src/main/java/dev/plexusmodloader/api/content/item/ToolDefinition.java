package dev.plexusmodloader.api.content.item;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;

/**
 * A tool item: a kind and a material, with the attack values of the vanilla tool of that kind unless
 * given. The attack damage the player deals is {@code 1 + attackDamage + material.attackDamageBonus()}.
 *
 * <p><b>Version differences:</b> before 1.21 (1.20.1 and 1.20.4) the game takes the attack damage of
 * swords, pickaxes, and hoes as a whole number, so those values are rounded there.
 *
 * @param kind what the tool is
 * @param material what it is made of
 * @param attackDamage damage before the material's bonus
 * @param attackSpeed attack speed modifier (the base speed is 4, so -2.4 gives 1.6 attacks a second)
 */
@ApiStability(StabilityLevel.PREVIEW)
public record ToolDefinition(ToolKind kind, ToolMaterialDefinition material, float attackDamage, float attackSpeed) {

    public ToolDefinition {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(material, "material");
    }

    /** A tool with the vanilla attack damage and speed of its kind. */
    public static ToolDefinition of(ToolKind kind, ToolMaterialDefinition material) {
        return new ToolDefinition(kind, material, kind.defaultAttackDamage(), kind.defaultAttackSpeed());
    }
}
