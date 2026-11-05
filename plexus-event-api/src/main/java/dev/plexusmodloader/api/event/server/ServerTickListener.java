package dev.plexusmodloader.api.event.server;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/**
 * Called on the server thread during a server tick (at the start for {@link ServerEvents#TICK}, or at
 * the end for {@link ServerEvents#TICK_END}) (20 times per second). Keep it fast.
 */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface ServerTickListener {
    /** Handles the event. */
    void onServerTick(ServerView server);
}
