package dev.plexusmodloader.compat.forge.modlauncher;

import net.neoforged.fml.loading.targets.ForgeClientLaunchHandler;

/**
 * The client on NeoForge 20.4 (FML 2, ADR-0043): Plexus Mod Loader's launch target {@code client}. NeoForge's own production
 * handler ({@code forgeclient}) with the mods Plexus Mod Loader found added to the jars FML reads, without FML's own options
 * in the game's arguments.
 */
public final class ClientLaunchHandler2 extends ForgeClientLaunchHandler {

    /** Called by the service loader. */
    public ClientLaunchHandler2() {}

    @Override
    public String name() {
        return "plexusmodloader_client";
    }

    @Override
    public LocatedPaths getMinecraftPaths() {
        return ModPaths2.withMods(super.getMinecraftPaths());
    }

    /** As {@link ServerLaunchHandler2#preLaunch}. */
    @Override
    protected String[] preLaunch(String[] arguments, ModuleLayer layer) {
        return FmlArguments.withoutFml(arguments);
    }
}
