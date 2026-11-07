package dev.plexusmodloader.api.event.interaction;

import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.event.EventResult;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import dev.plexusmodloader.api.world.BlockPos;
import dev.plexusmodloader.api.world.Direction;

/** Listener for {@link InteractionEvents#ATTACK_BLOCK}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface AttackBlockListener {

    /**
     * Called on the server thread when a player starts digging a block (the left-click press), before
     * the game begins to break it. The view is valid only during the call.
     *
     * @param player the player who started digging
     * @param pos the position of the block
     * @param face the face of the block the player clicked
     * @return {@code DENY} to refuse the dig: no breaking progress starts and the server resends the
     *     block to the player (nothing breaks, also not at once in creative mode); {@code ALLOW} or
     *     {@code PASS} to let the game go on
     */
    EventResult onAttackBlock(PlayerView player, BlockPos pos, Direction face);
}
