package dev.plexusmodloader.compat.forge.modlauncher;

/**
 * Mojang's game test server as Plexus Mod Loader's launch target {@code game_test_server} on Forge 47: not production
 * (Forge runs game tests only outside production), as {@code GameTestLaunchHandler} does for the later Forge builds.
 */
public final class GameTestLaunchHandler47 extends PlexusLaunchHandler47 {

    public GameTestLaunchHandler47() {
        super();
    }

    @Override
    public String name() {
        return "plexusmodloader_server_gametest";
    }

    @Override
    public boolean isProduction() {
        return false;
    }
}
