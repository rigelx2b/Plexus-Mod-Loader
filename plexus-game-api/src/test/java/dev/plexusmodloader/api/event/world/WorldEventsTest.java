package dev.plexusmodloader.api.event.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class WorldEventsTest {

    @Test
    void tickStartEventIsRegisteredWithCorrectKeyAndListener() {
        assertNotNull(WorldEvents.TICK_START);
        assertEquals("plexus:world/tick_start", WorldEvents.TICK_START.id());
        assertSame(WorldTickListener.class, WorldEvents.TICK_START.listenerType());
    }

    @Test
    void tickEndEventIsRegisteredWithCorrectKeyAndListener() {
        assertNotNull(WorldEvents.TICK_END);
        assertEquals("plexus:world/tick_end", WorldEvents.TICK_END.id());
        assertSame(WorldTickListener.class, WorldEvents.TICK_END.listenerType());
    }
}
