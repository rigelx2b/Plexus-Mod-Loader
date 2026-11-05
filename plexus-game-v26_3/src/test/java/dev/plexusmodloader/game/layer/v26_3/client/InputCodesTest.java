package dev.plexusmodloader.game.layer.v26_3.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.InputConstants;
import dev.plexusmodloader.api.client.KeyAction;
import dev.plexusmodloader.api.client.KeyCodes;
import dev.plexusmodloader.api.client.MouseButtons;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 26.3's SDL input values against Plexus Mod Loader's GLFW-based ones (ADR-0029). */
class InputCodesTest {

    @Test
    void keysGoBackToTheirPlexusCodes() {
        assertEquals(KeyCodes.A, InputCodes.key(InputConstants.KEY_A));
        assertEquals(KeyCodes.F6, InputCodes.key(InputConstants.KEY_F6));
        assertEquals(256, InputCodes.key(InputConstants.KEY_ESCAPE));
        assertEquals(340, InputCodes.key(InputConstants.KEY_LSHIFT));
        assertEquals(KeyCodes.UNBOUND, InputCodes.key(InputConstants.UNKNOWN.getValue()));
    }

    @Test
    void everyMappedKeyRoundTrips() {
        for (int plexus = 0; plexus < 400; plexus++) {
            int key = plexus;
            GameKeyCodes.toGame(key).ifPresent(game -> {
                if (key != KeyCodes.UNBOUND) {
                    assertEquals(key, InputCodes.key(game), "key " + key);
                }
            });
        }
    }

    @Test
    void actionsIncludeSdlsRepeatValue() {
        assertEquals(Optional.of(KeyAction.PRESS), InputCodes.action(1));
        assertEquals(Optional.of(KeyAction.RELEASE), InputCodes.action(0));
        assertEquals(Optional.of(KeyAction.REPEAT), InputCodes.action(-1));
        assertTrue(InputCodes.action(2).isEmpty(), "2 is GLFW's repeat, not 26.3's");
    }

    @Test
    void mouseButtonsUseGlfwNumbers() {
        assertEquals(MouseButtons.LEFT, InputCodes.mouseButton(InputConstants.MOUSE_BUTTON_LEFT));
        assertEquals(MouseButtons.RIGHT, InputCodes.mouseButton(InputConstants.MOUSE_BUTTON_RIGHT));
        assertEquals(MouseButtons.MIDDLE, InputCodes.mouseButton(InputConstants.MOUSE_BUTTON_MIDDLE));
        assertEquals(3, InputCodes.mouseButton(InputConstants.MOUSE_BUTTON_4));
        assertEquals(7, InputCodes.mouseButton(InputConstants.MOUSE_BUTTON_8));
        assertEquals(-1, InputCodes.mouseButton(0));
    }
}
