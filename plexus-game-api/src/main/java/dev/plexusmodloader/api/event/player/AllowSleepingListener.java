package dev.plexusmodloader.api.event.player;

import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.event.EventResult;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import dev.plexusmodloader.api.world.BlockPos;

/** Listener for {@link PlayerEvents#ALLOW_SLEEPING}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface AllowSleepingListener {

    /**
     * Called on the server thread when a player tries to lie down in a bed, after the click and before the game checks
     * the time of day, the distance, and nearby monsters. The view is valid only during the call.
     *
     * <p>The game's own checks still apply: {@code ALLOW} or {@code PASS} does not make sleeping possible when the game
     * would refuse it (daylight, monsters, too far away).
     *
     * @param player the player who clicked the bed
     * @param bed the position of the bed block the player clicked (the head half, as the game uses it)
     * @return {@code DENY} to refuse: the player stays up and the game shows no message (send one yourself with {@link
     *     PlayerView#sendMessage}); {@code ALLOW} or {@code PASS} to let the game decide
     */
    EventResult allowSleeping(PlayerView player, BlockPos bed);
}
