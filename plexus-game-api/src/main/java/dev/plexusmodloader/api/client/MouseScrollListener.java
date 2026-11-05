package dev.plexusmodloader.api.client;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link ClientEvents#MOUSE_SCROLL}. Called on the client thread; keep it fast. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface MouseScrollListener {

    /**
     * @param horizontal sideways scroll amount (positive to the right)
     * @param vertical scroll amount (positive away from the player, as the hotbar scrolls to the previous slot)
     */
    void onMouseScroll(ClientView client, double horizontal, double vertical);
}
