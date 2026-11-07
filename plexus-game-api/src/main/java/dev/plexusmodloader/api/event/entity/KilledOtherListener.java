package dev.plexusmodloader.api.event.entity;

import dev.plexusmodloader.api.entity.DamageInfo;
import dev.plexusmodloader.api.entity.EntityView;
import dev.plexusmodloader.api.entity.LivingEntityView;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link EntityEvents#KILLED_OTHER}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface KilledOtherListener {

    /**
     * Called on the server thread when a living entity dies and its damage source names another entity as the cause: a
     * player's blow, a mob's attack, or the owner of an arrow or other projectile. It fires right after {@code DIED}
     * for the same death and not when the entity died without a causing entity (fall, fire, commands). The views are
     * valid only during the call.
     *
     * @param killer the entity that caused the damage; for a projectile, the one who shot it
     * @param victim the entity that died
     * @param damage the damage source
     */
    void onKilledOther(EntityView killer, LivingEntityView victim, DamageInfo damage);
}
