package dev.plexusmodloader.api.event.world;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import dev.plexusmodloader.api.world.WorldView;

/**
 * Listener for dimension tick events: {@link WorldEvents#TICK_START} and {@link WorldEvents#TICK_END}.
 */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface WorldTickListener {

    /**
     * Called on the server thread during a dimension tick.
     *
     * @param world the dimension that is ticking; valid only during the call
     */
    void onWorldTick(WorldView world);
}
