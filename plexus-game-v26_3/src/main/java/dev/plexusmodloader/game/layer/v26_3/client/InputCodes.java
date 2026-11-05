package dev.plexusmodloader.game.layer.v26_3.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.plexusmodloader.api.client.KeyAction;
import java.util.Optional;

/**
 * Converts 26.3's input values to Plexus Mod Loader's (ADR-0029). 26.3 reads input through SDL: keys are SDL scancodes
 * ({@link GameKeyCodes}), mouse buttons count from 1 with right as 3, and a repeated key is {@code -1}
 * ({@code InputConstants}, checked with {@code javap}).
 */
final class InputCodes {

    private InputCodes() {}

    /** The Plexus Mod Loader key code (GLFW token) of a 26.3 key, or -1 for a key without one. */
    static int key(int gameKey) {
        return GameKeyCodes.toPlexus(gameKey);
    }

    /** The action of a key or button, empty for a value the game does not use. */
    static Optional<KeyAction> action(int gameAction) {
        return switch (gameAction) {
            case InputConstants.PRESS -> Optional.of(KeyAction.PRESS);
            case InputConstants.RELEASE -> Optional.of(KeyAction.RELEASE);
            case InputConstants.REPEAT -> Optional.of(KeyAction.REPEAT);
            default -> Optional.empty();
        };
    }

    /** The Plexus Mod Loader (GLFW) number of a 26.3 mouse button, or -1 for none. */
    static int mouseButton(int gameButton) {
        return switch (gameButton) {
            case InputConstants.MOUSE_BUTTON_LEFT -> 0;
            case InputConstants.MOUSE_BUTTON_RIGHT -> 1;
            case InputConstants.MOUSE_BUTTON_MIDDLE -> 2;
            default -> gameButton > InputConstants.MOUSE_BUTTON_RIGHT ? gameButton - 1 : -1;
        };
    }
}
