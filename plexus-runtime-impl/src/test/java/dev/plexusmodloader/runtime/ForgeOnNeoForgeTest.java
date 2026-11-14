package dev.plexusmodloader.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.compat.bridge.CompatibilityFinding;
import dev.plexusmodloader.runtime.discovery.DiscoveredModFile;
import dev.plexusmodloader.runtime.discovery.ModFileKind;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Forge on NeoForge, F-N1: when NeoForge hosts a game with Forge mods, and what each Forge mod is told. */
class ForgeOnNeoForgeTest {

    private static final String PROPERTY = "plexus.forgeOnNeoForge";
    private static final DiscoveredModFile FORGE = file("forge.jar", ModFileKind.FORGE);
    private static final DiscoveredModFile NEOFORGE = file("neoforge.jar", ModFileKind.NEOFORGE);
    private static final DiscoveredModFile FABRIC = file("fabric.jar", ModFileKind.FABRIC);

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void offByDefaultSoForgeStillHostsBesideNeoForgeMods() {
        List<DiscoveredModFile> files = List.of(FORGE, NEOFORGE);
        assertFalse(PlexusRuntime.forgeOnNeoForge(files, PlexusRuntime.forgeJars(files)));
    }

    @Test
    void onWithForgeAndNeoForgeModsNeoForgeHosts() {
        System.setProperty(PROPERTY, "on");
        List<DiscoveredModFile> files = List.of(FORGE, NEOFORGE, FABRIC);
        assertTrue(PlexusRuntime.forgeOnNeoForge(files, PlexusRuntime.forgeJars(files)));
    }

    /** Without NeoForge mods, or without Forge mods, the setting changes nothing. */
    @Test
    void onWithOnlyOneOfTheLoadersChangesNothing() {
        System.setProperty(PROPERTY, "on");
        List<DiscoveredModFile> onlyForge = List.of(FORGE, FABRIC);
        assertFalse(PlexusRuntime.forgeOnNeoForge(onlyForge, PlexusRuntime.forgeJars(onlyForge)));
        List<DiscoveredModFile> onlyNeoForge = List.of(NEOFORGE, FABRIC);
        assertFalse(PlexusRuntime.forgeOnNeoForge(onlyNeoForge, PlexusRuntime.forgeJars(onlyNeoForge)));
    }

    @Test
    void eachForgeModLeftOutIsBlockedWithCode229AndWhatItUses() {
        CompatibilityFinding finding =
                PlexusRuntime.forgeOnNeoForgeFinding(List.of("net/minecraftforge/fml/ModList", "MixinConfigs"));
        assertEquals("PLEXUS-COMPAT-229", finding.code());
        assertTrue(finding.blocking());
        assertTrue(finding.detail().endsWith("net/minecraftforge/fml/ModList, MixinConfigs"), finding.detail());
    }

    /** F-N2 hands NeoForge only the jars with Forge's metadata alone, not those built for NeoForge too. */
    @Test
    void onlyForgeJarsLeaveOutJarsForNeoForgeToo() {
        DiscoveredModFile both = new DiscoveredModFile(
                Path.of("both.jar"),
                ModFileKind.FORGE,
                Optional.empty(),
                Optional.empty(),
                Set.of(ModFileKind.NEOFORGE));
        assertEquals(
                List.of(Path.of("forge.jar")), PlexusRuntime.onlyForgeJars(List.of(FORGE, both, NEOFORGE, FABRIC)));
    }

    private static DiscoveredModFile file(String name, ModFileKind kind) {
        return new DiscoveredModFile(Path.of(name), kind, Optional.empty(), Optional.empty(), Set.of());
    }
}
