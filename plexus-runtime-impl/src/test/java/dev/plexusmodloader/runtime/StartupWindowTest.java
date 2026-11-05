package dev.plexusmodloader.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.plexusmodloader.game.version.GameLaunchTarget;
import dev.plexusmodloader.game.version.layer.LoadedModCounts;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupWindowTest {

    @Test
    void withoutWindowEveryStepDoesNothing() {
        StartupWindow startup = StartupWindow.none();
        startup.hostStarting("NeoForge");
        startup.discovered(3);
        startup.resolving();
        startup.modsLoaded(new LoadedModCounts(1, 0, 0, 0, 0));
        startup.preparing();
        startup.initialisingMods(1);
        startup.opening();
        startup.shown();
        startup.startingGame("26.2");
        startup.gameEnded();
        assertFalse(startup.shows());
    }

    @Test
    void disabledWhenPropertySetToOff(@TempDir Path tempDir) {
        String previous = System.getProperty("plexus.earlyWindow");
        try {
            System.setProperty("plexus.earlyWindow", "off");
            LaunchConfiguration config = new LaunchConfiguration(
                    GameLaunchTarget.CLIENT,
                    tempDir.resolve("game.jar"),
                    tempDir.resolve("mods"),
                    List.of(),
                    Optional.empty(),
                    tempDir.resolve("reports"),
                    Optional.empty());
            StartupWindow startup = StartupWindow.start(config, new TestGameVersionAdapter(), "26.3", new String[0]);
            assertFalse(startup.shows());
        } finally {
            if (previous != null) {
                System.setProperty("plexus.earlyWindow", previous);
            } else {
                System.clearProperty("plexus.earlyWindow");
            }
        }
    }

    @Test
    void disabledOnServerSide(@TempDir Path tempDir) {
        LaunchConfiguration config = new LaunchConfiguration(
                GameLaunchTarget.DEDICATED_SERVER,
                tempDir.resolve("game.jar"),
                tempDir.resolve("mods"),
                List.of(),
                Optional.empty(),
                tempDir.resolve("reports"),
                Optional.empty());
        StartupWindow startup = StartupWindow.start(config, new TestGameVersionAdapter(), "26.3", new String[0]);
        assertFalse(startup.shows());
    }
}
