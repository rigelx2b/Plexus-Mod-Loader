package dev.plexusmodloader.api.event.entity;

import dev.plexusmodloader.api.entity.DamageInfo;
import dev.plexusmodloader.api.entity.LivingEntityView;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link EntityEvents#AFTER_DAMAGE}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface AfterDamageListener {

    /**
     * Called on the server thread after a living entity lost health to damage. It does not fire when the
     * damage was cancelled, when armour or resistance reduced it to nothing, or for health lost without
     * damage (poison finishing, commands setting health). The entity may be dead: check {@code isAlive()}.
     * The views are valid only during the call.
     *
     * @param entity the damaged entity
     * @param damage the damage source; {@link DamageInfo#amount()} is the amount dealt before armour,
     *     enchantments, and absorption (after {@code ALLOW_DAMAGE} and {@code MODIFY_DAMAGE})
     * @param healthLost the health the entity actually lost, always above zero
     */
    void afterDamage(LivingEntityView entity, DamageInfo damage, float healthLost);
}
