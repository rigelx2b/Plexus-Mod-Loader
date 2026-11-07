package dev.plexusmodloader.compat.forge.modlauncher;

import java.io.File;
import java.nio.file.Path;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * FML 4's mods folder, as Plexus Mod Loader sees it (ADR-0043): the NeoForge mods Plexus Mod Loader found and left in. FML's
 * own {@code ModsFolderLocator} is left out of the BOOT layer ({@code ForgeBootModules}), so FML never takes jars of other
 * loaders for its own or reports them as files it cannot read. The paths come from the system property {@value #MODS}, set
 * by the host before FML starts, because FML creates this class by itself (a ModLauncher service provider).
 */
public final class PlexusModsLocator4 implements IModFileCandidateLocator {

    /** The system property with the mod jars, separated by {@link File#pathSeparator}. */
    static final String MODS = "plexus.forge.mods";

    /** Called by the service loader. */
    public PlexusModsLocator4() {}

    /**
     * Each jar is a NeoForge mod Plexus Mod Loader read, so a jar FML cannot read is an error, as from FML's folder. A jar
     * FML already located is left to FML (an early service jar, whose nested mods FML takes from its JarJar).
     */
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        for (Path path : ModPathList.parse(System.getProperty(MODS))) {
            if (context.isLocated(path)) {
                continue;
            }
            pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR);
        }
    }

    @Override
    public String toString() {
        return "{Plexus Mod Loader's NeoForge mods}";
    }
}
