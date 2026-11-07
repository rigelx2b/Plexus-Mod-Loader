package dev.plexusmodloader.compat.forge.modlauncher;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.minecraftforge.fml.loading.VersionInfo;
import net.minecraftforge.fml.loading.targets.CommonClientLaunchHandler;

/**
 * The client on Forge 47 (Minecraft 1.20.1): Plexus Mod Loader's launch target {@code client}, production. FML 47's own {@code
 * forgeclient} looks for the client jars in a Maven layout under {@code libraryDirectory}; this one takes the patched client
 * jar and FML's jars from the system properties, as {@link PlexusLaunchHandler47} does for the server.
 */
public final class ClientLaunchHandler47 extends CommonClientLaunchHandler {

    public ClientLaunchHandler47() {
        super();
    }

    @Override
    public String name() {
        return "plexusmodloader_client";
    }

    @Override
    public String getNaming() {
        return "mcp";
    }

    @Override
    public LocatedPaths getMinecraftPaths() {
        return Paths47.locatedPaths();
    }

    @Override
    protected void processMCStream(VersionInfo versionInfo, Stream.Builder<Path> mc, Stream.Builder<List<Path>> mods) {
        // Not used: getMinecraftPaths() does not look for Minecraft in FML's library directory.
    }

    @Override
    protected void runTarget(String mainClass, String[] args, ModuleLayer layer) throws Throwable {
        super.runTarget(mainClass, Paths47.withoutFmlOptions(args), layer);
    }
}
