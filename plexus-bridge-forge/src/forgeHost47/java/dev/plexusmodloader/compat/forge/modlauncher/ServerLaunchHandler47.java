package dev.plexusmodloader.compat.forge.modlauncher;

/** The dedicated server on Forge 47 (Minecraft 1.20.1): Plexus Mod Loader's launch target {@code server}, production. */
public final class ServerLaunchHandler47 extends PlexusLaunchHandler47 {

    public ServerLaunchHandler47() {
        super();
    }

    @Override
    public String name() {
        return "plexusmodloader_server";
    }

    @Override
    public boolean isProduction() {
        return true;
    }
}
