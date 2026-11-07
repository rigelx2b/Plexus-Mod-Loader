package dev.plexusmodloader.api.event.player;

import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link PlayerEvents#TICK}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface PlayerTickListener {

    /**
     * Called on the server thread at the start of every tick of a server player that is in a world.
     * Keep it cheap: it runs once per player per tick (20 times a second). The view is valid only
     * during the call.
     *
     * <p>A listener that throws is disabled for the rest of the session ({@code PLEXUS-EVENT-001}),
     * as for every event.
     *
     * @param player the player being ticked
     */
    void onPlayerTick(PlayerView player);
}
