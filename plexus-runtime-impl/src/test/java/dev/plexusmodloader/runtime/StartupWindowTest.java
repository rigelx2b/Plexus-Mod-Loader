package dev.plexusmodloader.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.plexusmodloader.game.version.layer.LoadedModCounts;
import org.junit.jupiter.api.Test;

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
        assertEquals(false, startup.shows());
    }
}
