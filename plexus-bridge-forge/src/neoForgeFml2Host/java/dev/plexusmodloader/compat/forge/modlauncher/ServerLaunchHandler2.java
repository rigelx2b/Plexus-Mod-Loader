package dev.plexusmodloader.compat.forge.modlauncher;

import net.neoforged.fml.loading.targets.ForgeServerLaunchHandler;

/**
 * The dedicated server on NeoForge 20.4 (FML 2, ADR-0043): Plexus Mod Loader's launch target {@code server}. NeoForge's own
 * production handler ({@code forgeserver}: Minecraft and NeoForge from the libraries folder) with the mods Plexus Mod Loader
 * found added to the jars FML reads, without FML's own options in the game's arguments.
 */
public final class ServerLaunchHandler2 extends ForgeServerLaunchHandler {

    /** Called by the service loader. */
    public ServerLaunchHandler2() {}

    @Override
    public String name() {
        return "plexusmodloader_server";
    }

    @Override
    public LocatedPaths getMinecraftPaths() {
        return ModPaths2.withMods(super.getMinecraftPaths());
    }

    /**
     * Not FML's {@code preLaunch}: it replaces Log4j's configuration whose plugins the game class loader does not find; the
     * configuration Plexus Mod Loader's run was started with stays.
     */
    @Override
    protected String[] preLaunch(String[] arguments, ModuleLayer layer) {
        return FmlArguments.withoutFml(arguments);
    }
}
