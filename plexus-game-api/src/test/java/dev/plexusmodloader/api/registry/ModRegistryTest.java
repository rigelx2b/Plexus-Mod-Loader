package dev.plexusmodloader.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.resource.ResourceId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModRegistryTest {

    record Spell(String name) {}

    private static ResourceId id(String path) {
        return ResourceId.of("registry_test", path);
    }

    @Test
    void entriesGetRawIdsInRegistrationOrder() {
        ModRegistry<Spell> spells = ModRegistry.create(id("order"), Spell.class);
        Spell fire = spells.register(id("fire"), new Spell("fire"));
        Spell ice = spells.register(id("ice"), new Spell("ice"));

        assertEquals(0, spells.rawId(id("fire")));
        assertEquals(1, spells.rawId(id("ice")));
        assertEquals(-1, spells.rawId(id("none")));
        assertSame(ice, spells.byRawId(1).orElseThrow());
        assertEquals(Optional.empty(), spells.byRawId(2));
        assertEquals(Optional.of(id("fire")), spells.idOf(fire));
        assertEquals(List.of(id("fire"), id("ice")), spells.keys());
        assertEquals(List.of(fire, ice), spells.values());
        assertEquals(2, spells.size());
        assertSame(fire, spells.require(id("fire")));
        assertThrows(NoSuchElementException.class, () -> spells.require(id("none")));
    }

    @Test
    void duplicateIdsAndObjectsAreRefused() {
        ModRegistry<Spell> spells = ModRegistry.create(id("duplicates"), Spell.class);
        Spell fire = spells.register(id("fire"), new Spell("fire"));

        assertThrows(IllegalArgumentException.class, () -> spells.register(id("fire"), new Spell("other")));
        assertThrows(IllegalArgumentException.class, () -> spells.register(id("again"), fire));
        assertEquals(1, spells.size());
    }

    @Test
    void freezeClosesTheRegistryAndRunsListenersOnce() {
        ModRegistry<Spell> spells = ModRegistry.create(id("freeze"), Spell.class);
        AtomicInteger frozenCalls = new AtomicInteger();
        spells.onFreeze(registry -> frozenCalls.incrementAndGet());
        spells.register(id("fire"), new Spell("fire"));

        assertFalse(spells.isFrozen());
        spells.freeze();
        spells.freeze();

        assertTrue(spells.isFrozen());
        assertEquals(1, frozenCalls.get());
        assertThrows(IllegalStateException.class, () -> spells.register(id("ice"), new Spell("ice")));
        spells.onFreeze(registry -> frozenCalls.incrementAndGet());
        assertEquals(2, frozenCalls.get());
    }

    @Test
    void registriesAreFoundByIdAndTypeAndNamesAreUnique() {
        ModRegistry<Spell> spells = ModRegistry.create(id("lookup"), Spell.class);

        assertSame(spells, ModRegistry.find(id("lookup"), Spell.class).orElseThrow());
        assertTrue(ModRegistry.find(id("lookup"), String.class).isEmpty());
        assertTrue(ModRegistry.find(id("missing"), Spell.class).isEmpty());
        assertThrows(IllegalStateException.class, () -> ModRegistry.create(id("lookup"), Spell.class));
    }
}
