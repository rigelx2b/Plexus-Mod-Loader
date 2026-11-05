package dev.plexusmodloader.api.content.attribute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AttributeDefinitionTest {

    @Test
    void ofCreatesAnUnsyncedAttributeForEveryLivingEntity() {
        AttributeDefinition definition = AttributeDefinition.of(1.0, 0.0, 10.0);

        assertEquals(1.0, definition.defaultValue());
        assertEquals(0.0, definition.minValue());
        assertEquals(10.0, definition.maxValue());
        assertFalse(definition.syncedToClients());
        assertEquals(AttributeScope.LIVING_ENTITIES, definition.scope());
    }

    @Test
    void withClientSyncOnlyTurnsSyncOn() {
        AttributeDefinition base = AttributeDefinition.of(1.0, 0.0, 10.0).withScope(AttributeScope.PLAYERS);

        AttributeDefinition synced = base.withClientSync();

        assertTrue(synced.syncedToClients());
        assertEquals(base.defaultValue(), synced.defaultValue());
        assertEquals(base.minValue(), synced.minValue());
        assertEquals(base.maxValue(), synced.maxValue());
        assertEquals(AttributeScope.PLAYERS, synced.scope());
    }

    @Test
    void withScopeKeepsTheSyncChoice() {
        AttributeDefinition synced = AttributeDefinition.of(0.5, 0.0, 1.0).withClientSync();

        AttributeDefinition players = synced.withScope(AttributeScope.PLAYERS);

        assertEquals(AttributeScope.PLAYERS, players.scope());
        assertTrue(players.syncedToClients());
    }

    @Test
    void theDefaultMayEqualTheBounds() {
        AttributeDefinition.of(0.0, 0.0, 5.0);
        AttributeDefinition.of(5.0, 0.0, 5.0);
        AttributeDefinition.of(3.0, 3.0, 3.0);
    }

    @Test
    void rejectsValuesThatAreNotFinite() {
        assertThrows(IllegalArgumentException.class, () -> AttributeDefinition.of(Double.NaN, 0.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> AttributeDefinition.of(0.5, Double.NaN, 1.0));
        assertThrows(IllegalArgumentException.class, () -> AttributeDefinition.of(0.5, 0.0, Double.POSITIVE_INFINITY));
        assertThrows(
                IllegalArgumentException.class,
                () -> AttributeDefinition.of(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0));
    }

    @Test
    void rejectsAnInvertedRange() {
        assertThrows(IllegalArgumentException.class, () -> AttributeDefinition.of(1.0, 2.0, 1.0));
    }

    @Test
    void rejectsADefaultOutsideTheRange() {
        assertThrows(IllegalArgumentException.class, () -> AttributeDefinition.of(-0.1, 0.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> AttributeDefinition.of(1.1, 0.0, 1.0));
    }

    @Test
    void rejectsANullScope() {
        assertThrows(NullPointerException.class, () -> new AttributeDefinition(1.0, 0.0, 2.0, false, null));
    }
}
