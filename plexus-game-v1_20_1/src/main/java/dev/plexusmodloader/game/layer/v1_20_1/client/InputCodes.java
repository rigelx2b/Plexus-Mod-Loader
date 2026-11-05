package dev.plexusmodloader.game.layer.v1_20_1.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.plexusmodloader.api.client.KeyAction;
import java.util.Optional;

/**
 * Converts 1.20.1's input values to Plexus Mod Loader's (ADR-0029). 1.20.1 reads input through GLFW, whose key tokens,
 * mouse button numbers, and actions Plexus Mod Loader uses, so only the action needs a name ({@code InputConstants},
 * checked with {@code javap}).
 */
final class InputCodes {

    private InputCodes() {}

    /** The Plexus Mod Loader key code of a 1.20.1 key: the same GLFW token. */
    static int key(int gameKey) {
        return gameKey;
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

    /** The Plexus Mod Loader number of a 1.20.1 mouse button: the same GLFW number, or -1 for none. */
    static int mouseButton(int gameButton) {
        return gameButton >= InputConstants.MOUSE_BUTTON_LEFT ? gameButton : -1;
    }
}
