package dev.plexusmodloader.api.client;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link ClientEvents#KEY_INPUT}. Called on the client thread; keep it fast. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface KeyInputListener {

    /**
     * @param key the key, one of {@link KeyCodes} (a GLFW key token on every version), or {@link KeyCodes#UNBOUND}
     *     for a key the running version cannot name that way
     * @param action press, release, or repeat
     */
    void onKeyInput(ClientView client, int key, KeyAction action);
}
