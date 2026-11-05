package dev.plexusmodloader.game.layer.v1_21_11.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.InputConstants;
import dev.plexusmodloader.api.client.KeyAction;
import dev.plexusmodloader.api.client.KeyCodes;
import dev.plexusmodloader.api.client.MouseButtons;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 1.21.11's GLFW input values against Plexus Mod Loader's (ADR-0029). */
class InputCodesTest {

    @Test
    void keysAreTheSameGlfwTokens() {
        assertEquals(KeyCodes.A, InputCodes.key(InputConstants.KEY_A));
        assertEquals(KeyCodes.F6, InputCodes.key(InputConstants.KEY_F6));
    }

    @Test
    void actions() {
        assertEquals(Optional.of(KeyAction.PRESS), InputCodes.action(InputConstants.PRESS));
        assertEquals(Optional.of(KeyAction.RELEASE), InputCodes.action(InputConstants.RELEASE));
        assertEquals(Optional.of(KeyAction.REPEAT), InputCodes.action(InputConstants.REPEAT));
        assertTrue(InputCodes.action(-1).isEmpty());
    }

    @Test
    void mouseButtons() {
        assertEquals(MouseButtons.LEFT, InputCodes.mouseButton(InputConstants.MOUSE_BUTTON_LEFT));
        assertEquals(MouseButtons.RIGHT, InputCodes.mouseButton(InputConstants.MOUSE_BUTTON_RIGHT));
        assertEquals(MouseButtons.MIDDLE, InputCodes.mouseButton(InputConstants.MOUSE_BUTTON_MIDDLE));
        assertEquals(-1, InputCodes.mouseButton(-1));
    }
}
