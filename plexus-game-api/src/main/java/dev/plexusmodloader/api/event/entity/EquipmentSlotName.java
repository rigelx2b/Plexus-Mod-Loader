package dev.plexusmodloader.api.event.entity;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/**
 * An equipment slot of a living entity, as {@link EntityEvents#EQUIPMENT_CHANGED} names it. {@code BODY} (horse armor,
 * wolf armor) exists since Minecraft 1.20.5 and {@code SADDLE} since 1.21.5, so the event never reports them on older
 * versions.
 */
@ApiStability(StabilityLevel.PREVIEW)
public enum EquipmentSlotName {
    MAINHAND,
    OFFHAND,
    FEET,
    LEGS,
    CHEST,
    HEAD,
    BODY,
    SADDLE
}
