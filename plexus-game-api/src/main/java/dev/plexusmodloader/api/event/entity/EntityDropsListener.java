package dev.plexusmodloader.api.event.entity;

import dev.plexusmodloader.api.entity.DamageInfo;
import dev.plexusmodloader.api.entity.LivingEntityView;
import dev.plexusmodloader.api.event.EventResult;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link EntityEvents#DROPS}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface EntityDropsListener {

    /**
     * Called on the server thread when a living entity dies, before the game drops its loot table items and
     * its equipment. The views are valid only during the call.
     *
     * @param entity the entity that died
     * @param cause the damage that killed it; {@link DamageInfo#amount()} is the entity's maximum health
     * @return {@code DENY} to drop no loot and no equipment (experience still drops); {@code ALLOW} or
     *     {@code PASS} to let the game drop them
     */
    EventResult onDrops(LivingEntityView entity, DamageInfo cause);
}
