package dev.plexusmodloader.api.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ClientEventsTest {

    @Test
    void keyInputEventHasItsOwnKeyAndListener() {
        assertEquals("plexus:client/key_input", ClientEvents.KEY_INPUT.id());
        assertSame(KeyInputListener.class, ClientEvents.KEY_INPUT.listenerType());
    }

    @Test
    void mouseButtonEventHasItsOwnKeyAndListener() {
        assertEquals("plexus:client/mouse_button", ClientEvents.MOUSE_BUTTON.id());
        assertSame(MouseButtonListener.class, ClientEvents.MOUSE_BUTTON.listenerType());
    }

    @Test
    void mouseScrollEventHasItsOwnKeyAndListener() {
        assertEquals("plexus:client/mouse_scroll", ClientEvents.MOUSE_SCROLL.id());
        assertSame(MouseScrollListener.class, ClientEvents.MOUSE_SCROLL.listenerType());
    }

    @Test
    void mouseButtonNumbersAreTheGlfwOnes() {
        assertEquals(0, MouseButtons.LEFT);
        assertEquals(1, MouseButtons.RIGHT);
        assertEquals(2, MouseButtons.MIDDLE);
    }

    @Test
    void keyActionsAreTheThreeThatTheGameReports() {
        assertEquals(Set.of(KeyAction.PRESS, KeyAction.RELEASE, KeyAction.REPEAT), Set.of(KeyAction.values()));
    }
}
