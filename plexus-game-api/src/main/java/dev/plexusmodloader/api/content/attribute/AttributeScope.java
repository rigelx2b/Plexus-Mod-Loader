package dev.plexusmodloader.api.content.attribute;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Which entities carry a mod attribute. */
@ApiStability(StabilityLevel.PREVIEW)
public enum AttributeScope {
    /** Players only. */
    PLAYERS,
    /** Every living entity: players, the game's mobs, armour stands, and mod mobs. */
    LIVING_ENTITIES
}
