package dev.plexusmodloader.compat.forge.modlauncher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import net.minecraftforge.fml.loading.targets.CommonLaunchHandler.LocatedPaths;
import org.junit.jupiter.api.Test;

/** What the Forge 47 launch handlers give FML 47: the patched game as the Minecraft mod, FML's jars as the rest. */
class Paths47Test {

    private static final String LIBRARIES = "libraries/net/minecraftforge/";

    @Test
    void theGameJarIsMinecraftTheUniversalJarIsTheForgeModAndTheOthersAreArtifacts() {
        String modFiles = String.join(
                File.pathSeparator,
                LIBRARIES + "forge/1.20.1-47.4.28/forge-1.20.1-47.4.28-universal.jar",
                LIBRARIES + "fmlcore/1.20.1-47.4.28/fmlcore-1.20.1-47.4.28.jar",
                LIBRARIES + "javafmllanguage/1.20.1-47.4.28/javafmllanguage-1.20.1-47.4.28.jar",
                LIBRARIES + "lowcodelanguage/1.20.1-47.4.28/lowcodelanguage-1.20.1-47.4.28.jar",
                LIBRARIES + "mclanguage/1.20.1-47.4.28/mclanguage-1.20.1-47.4.28.jar");

        LocatedPaths paths = Paths47.locatedPaths("cache/mojang-client/forge-1.20.1-47.4.28-client.jar", modFiles);

        assertEquals(List.of(Path.of("cache/mojang-client/forge-1.20.1-47.4.28-client.jar")), paths.minecraftPaths());
        assertEquals(
                List.of(List.of(Path.of(LIBRARIES + "forge/1.20.1-47.4.28/forge-1.20.1-47.4.28-universal.jar"))),
                paths.otherModPaths());
        assertEquals(4, paths.otherArtifacts().size());
        assertTrue(paths.otherArtifacts().stream().anyMatch(path -> path.endsWith("fmlcore-1.20.1-47.4.28.jar")));
        assertTrue(paths.minecraftFilter().test("net/minecraft/Foo.class", "anything"));
    }

    @Test
    void theForgeModsPlexusFoundAreModsOfFmlToo() {
        String mods = String.join(File.pathSeparator, "mods/jade.jar", "", "mods/architectury.jar");

        LocatedPaths paths = Paths47.locatedPaths(
                "cache/forge-client.jar", LIBRARIES + "forge/1.20.1-47.4.28/forge-1.20.1-47.4.28-universal.jar", mods);

        assertEquals(
                List.of(
                        List.of(Path.of(LIBRARIES + "forge/1.20.1-47.4.28/forge-1.20.1-47.4.28-universal.jar")),
                        List.of(Path.of("mods/jade.jar")),
                        List.of(Path.of("mods/architectury.jar"))),
                paths.otherModPaths());
    }

    @Test
    void withoutTheGameJarThereIsNothingToLaunch() {
        assertThrows(IllegalStateException.class, () -> Paths47.locatedPaths(null, ""));
    }

    @Test
    void emptyEntriesOfTheClassPathAreIgnored() {
        LocatedPaths paths = Paths47.locatedPaths("game.jar", File.pathSeparator + File.pathSeparator);

        assertEquals(List.of(), paths.otherArtifacts());
        assertEquals(List.of(), paths.otherModPaths());
    }

    @Test
    void fmlOptionsAreRemovedWithTheirValuesAndOtherArgumentsKept() {
        String[] args = {
            "--fml.forgeVersion",
            "47.4.28",
            "--username",
            "Dev",
            "--fml.mcpVersion",
            "20230612.114412",
            "--demo",
            "--fml.x"
        };

        assertArrayEquals(new String[] {"--username", "Dev", "--demo", "--fml.x"}, Paths47.withoutFmlOptions(args));
    }
}
