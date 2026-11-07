package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ADR-0043: which NeoForge releases run on ModLauncher (FML 4), and the launch target each Plexus target maps to. */
class NeoForgeFml4GameHostTest {

    @Test
    void neoForgeBefore21Point2RunsOnModLauncher() {
        assertTrue(NeoForgeFml4GameHost.runsOnModLauncher("21.1.265"));
        assertTrue(NeoForgeFml4GameHost.runsOnModLauncher("20.4.253"));
        assertTrue(NeoForgeFml4GameHost.runsOnModLauncher("21.0.167"));
    }

    @Test
    void neoForgeFrom21Point11OnHasNoModLauncher() {
        assertFalse(NeoForgeFml4GameHost.runsOnModLauncher("21.11.48"));
        assertFalse(NeoForgeFml4GameHost.runsOnModLauncher("26.2.0.93"));
        assertFalse(NeoForgeFml4GameHost.runsOnModLauncher("26.1.0.19-beta"));
        assertFalse(NeoForgeFml4GameHost.runsOnModLauncher("21"));
    }

    @Test
    void plexusTargetsMapToFmlsLaunchHandlers() {
        assertEquals("plexusmodloader_client", NeoForgeFml4GameHost.launchTarget("client"));
        assertEquals("plexusmodloader_server", NeoForgeFml4GameHost.launchTarget("server"));
        assertEquals("plexusmodloader_server_gametest", NeoForgeFml4GameHost.launchTarget("game_test_server"));
    }
}
