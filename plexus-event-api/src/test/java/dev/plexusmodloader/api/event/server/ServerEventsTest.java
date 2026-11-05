package dev.plexusmodloader.api.event.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ServerEventsTest {

    @Test
    void tickEndEventIsRegisteredWithCorrectKeyAndListener() {
        assertNotNull(ServerEvents.TICK_END);
        assertEquals("plexus:server/tick_end", ServerEvents.TICK_END.id());
        assertSame(ServerTickListener.class, ServerEvents.TICK_END.listenerType());
    }

    @Test
    void tickEventRemainsUnchanged() {
        assertNotNull(ServerEvents.TICK);
        assertEquals("plexus:server/tick", ServerEvents.TICK.id());
        assertSame(ServerTickListener.class, ServerEvents.TICK.listenerType());
    }
}
