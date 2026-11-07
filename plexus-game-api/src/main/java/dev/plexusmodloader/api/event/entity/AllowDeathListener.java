package dev.plexusmodloader.api.event.entity;

import dev.plexusmodloader.api.entity.DamageInfo;
import dev.plexusmodloader.api.entity.LivingEntityView;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link EntityEvents#ALLOW_DEATH}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface AllowDeathListener {

    /**
     * Called on the server thread when damage brought a living entity's health to zero, before the game
     * lets it die (and before the game's own death protection, a totem of undying, is tried).
     * Returning {@code false} keeps the entity alive with 1 health (half a heart); the damage that
     * killed it stays dealt. Any listener returning {@code false} is enough. The views are valid only
     * during the call.
     *
     * <p>This fires for every death caused by damage, including the kill command and falling out of the
     * world. A listener that wants to spare the entity only in some cases checks {@link
     * DamageInfo#type()}.
     *
     * @param entity the entity that would die
     * @param damage the damage source; {@link DamageInfo#amount()} is the entity's maximum health, not
     *     the amount dealt, because the game does not keep the amount past the damage step
     * @return {@code false} to keep the entity alive
     */
    boolean allowDeath(LivingEntityView entity, DamageInfo damage);
}
