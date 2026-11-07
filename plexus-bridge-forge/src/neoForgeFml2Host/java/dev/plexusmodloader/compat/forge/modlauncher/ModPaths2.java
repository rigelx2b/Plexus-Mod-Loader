package dev.plexusmodloader.compat.forge.modlauncher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.loading.targets.CommonLaunchHandler.LocatedPaths;

/**
 * The mods Plexus Mod Loader found, handed to NeoForge's FML 2 (NeoForge 20.4, ADR-0043) in the list of jars its Minecraft
 * locator reads as mods. FML 2 has no candidate locator API of FML 4's shape and its mods folder locator is left out of the
 * BOOT layer ({@code ForgeBootModules}), so the jars come with the paths of Minecraft and NeoForge, as for Forge 47 ({@code
 * Paths47}). The paths are in the system property {@value #MODS}, set by the host before FML starts.
 */
final class ModPaths2 {

    /** The system property with the mod jars, separated by {@link java.io.File#pathSeparator}. */
    static final String MODS = "plexus.forge.mods";

    private ModPaths2() {}

    /** {@code located} (Minecraft, NeoForge) with each mod jar as one more mod. */
    static LocatedPaths withMods(LocatedPaths located) {
        // Without the jars in ModLauncher's SERVICE layer, as FML's mods folder locator leaves those out.
        return withMods(located, ServiceJars.without(ModPathList.parse(System.getProperty(MODS))));
    }

    static LocatedPaths withMods(LocatedPaths located, List<Path> mods) {
        List<List<Path>> otherMods = new ArrayList<>(located.otherModPaths());
        for (Path mod : mods) {
            otherMods.add(List.of(mod));
        }
        return new LocatedPaths(
                located.minecraftPaths(), located.minecraftFilter(), List.copyOf(otherMods), located.otherArtifacts());
    }
}
