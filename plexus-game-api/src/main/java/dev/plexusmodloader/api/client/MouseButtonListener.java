package dev.plexusmodloader.api.client;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link ClientEvents#MOUSE_BUTTON}. Called on the client thread; keep it fast. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface MouseButtonListener {

    /**
     * @param button the button, one of {@link MouseButtons} (GLFW numbers on every version)
     * @param action {@link KeyAction#PRESS} or {@link KeyAction#RELEASE}
     */
    void onMouseButton(ClientView client, int button, KeyAction action);
}
