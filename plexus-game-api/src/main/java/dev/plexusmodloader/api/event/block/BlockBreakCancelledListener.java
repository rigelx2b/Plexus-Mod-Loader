package dev.plexusmodloader.api.event.block;

import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import dev.plexusmodloader.api.world.BlockPos;
import dev.plexusmodloader.api.world.WorldView;

/** Listener for {@link BlockEvents#BREAK_CANCELLED}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface BlockBreakCancelledListener {

    /**
     * Called on the server thread after a {@link BlockEvents#BREAK} listener refused a break; the block
     * is still in the world. Use it to undo what a listener prepared for the break, or to tell the
     * player why the block stays.
     *
     * @param world the world the block is in
     * @param pos where it is
     * @param block the block that was not broken
     * @param player who tried to break it
     */
    void onBlockBreakCancelled(WorldView world, BlockPos pos, ResourceId block, PlayerView player);
}
