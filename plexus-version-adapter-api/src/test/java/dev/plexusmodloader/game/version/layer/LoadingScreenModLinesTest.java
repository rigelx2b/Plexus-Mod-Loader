package dev.plexusmodloader.game.version.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LoadingScreenModLinesTest {

    @Test
    void theLoadersWhoseModsRunInOneLine() {
        assertEquals(List.of(), LoadingScreenModLines.lines(LoadedModCounts.NONE, 70));
        assertEquals(
                List.of("Mods: Fabric 1  ·  Quilt 2  ·  Forge 3"),
                LoadingScreenModLines.lines(new LoadedModCounts(0, 1, 3, 2, 0), 70));
    }

    /** Sodium and Iris, with 13 Fabric API modules and libraries inside them, next to two NeoForge mods. */
    @Test
    void bundledModsAreInBracketsAfterTheInstalledOnes() {
        LoadedModCounts counts =
                new LoadedModCounts(0, 15, 0, 0, 2).withInstalled(new LoadedModCounts.Installed(0, 2, 0, 0, 2));

        assertEquals("Mods: Fabric 2 (+13 included)  ·  NeoForge 2", LoadingScreenModLines.countsLine(counts));
    }

    @Test
    void modsNotLoadedHaveWarningLinesAfterTheCounts() {
        LoadedModCounts counts = new LoadedModCounts(0, 15, 0, 0, 0)
                .withHostIncompatibleMods(2)
                .withLeftOutMods(List.of("sophisticatedbackpacks"));

        assertEquals(
                List.of(
                        "Mods: Fabric 15",
                        "2 not loaded: not yet supported by Plexus Mod Loader",
                        "1 not loaded: sophisticatedbackpacks - see logs/latest.log"),
                LoadingScreenModLines.lines(counts, 70));
    }

    /** With no mod running, the warnings still start on the second line, which the screens draw in amber. */
    @Test
    void withoutModsTheFirstLineIsEmpty() {
        assertEquals(
                List.of("", "1 not loaded: broken.jar - see logs/latest.log"),
                LoadingScreenModLines.lines(LoadedModCounts.NONE.withLeftOutMods(List.of("broken.jar")), 70));
    }

    @Test
    void namesThatDoNotFitAreCounted() {
        List<String> mods = List.of("sophisticatedbackpacks", "sophisticatedstorage", "another_long_mod_id");

        String line = LoadingScreenModLines.leftOutLine(mods, 70);
        assertEquals("3 not loaded: sophisticatedbackpacks and 2 more - see logs/latest.log", line);
        assertTrue(line.length() <= 70);
        assertEquals("3 not loaded - see logs/latest.log", LoadingScreenModLines.leftOutLine(mods, 40));
    }
}
