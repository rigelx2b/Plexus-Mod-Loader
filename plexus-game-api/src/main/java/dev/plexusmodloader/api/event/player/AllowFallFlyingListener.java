package dev.plexusmodloader.api.event.player;

import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.event.EventResult;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link PlayerEvents#ALLOW_FALL_FLYING}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface AllowFallFlyingListener {

    /**
     * Called on the server thread when a player who wears working gliding gear (elytra) is about to start gliding: the
     * jump key pressed in the air. It is not called for players who could not glide anyway (on the ground, in water,
     * no elytra), and not again while the player keeps gliding. The view is valid only during the call.
     *
     * <p>A listener can only forbid gliding; it cannot make a player without elytra glide.
     *
     * @param player the player who tries to start gliding
     * @return {@code DENY} to keep the player from starting to glide; {@code ALLOW} or {@code PASS} to let it start
     */
    EventResult allowFallFlying(PlayerView player);
}
