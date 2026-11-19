package dev.plexusmodloader.compat.forge.fml;

import java.nio.file.Path;

/**
 * Forge on NeoForge (ADR-0051): Forge's paths ({@code net.minecraftforge.fml.loading.FMLPaths}, in Forge's {@code
 * fmlloader} jar among the loader libraries) for the game NeoForge runs. MinecraftForge's loader sets them from its
 * launch environment; here Forge's own {@code FMLPaths.loadAbsolutePaths} sets them to NeoForge's game directory, so
 * that a Forge mod's {@code FMLPaths.CONFIGDIR.get()} is the same {@code config} folder NeoForge's mods use.
 *
 * <p>Thread: FML's construct stage; set once.
 */
final class ForgePaths {

    private static boolean set;

    private ForgePaths() {}

    /** Sets Forge's paths to NeoForge's game directory, once. */
    static synchronized void setToGameDirectory() {
        if (set) {
            return;
        }
        Path gameDirectory = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
        net.minecraftforge.fml.loading.FMLPaths.loadAbsolutePaths(gameDirectory);
        set = true;
    }
}
