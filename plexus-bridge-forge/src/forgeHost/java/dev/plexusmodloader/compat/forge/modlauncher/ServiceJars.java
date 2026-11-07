package dev.plexusmodloader.compat.forge.modlauncher;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mod jars {@code PlexusModLauncher} put into ModLauncher's SERVICE layer (they provide a transformation service, a mod
 * locator or a dependency locator). FML's own mods folder locator leaves such jars out of its candidates
 * ({@code ModDirTransformerDiscoverer.allExcluded()}), because the layer holds them already and the game class loader
 * cannot define a module twice. The launch handlers that hand FML the mods Plexus Mod Loader found do the same with this
 * list (ADR-0043).
 */
public final class ServiceJars {

    private static final Set<Path> PATHS = ConcurrentHashMap.newKeySet();

    private ServiceJars() {}

    /** Remembers {@code jar} as a jar of the SERVICE layer. */
    static void record(Path jar) {
        PATHS.add(jar.toAbsolutePath().normalize());
    }

    /** {@code jars} without those {@link #record recorded} as jars of the SERVICE layer, in order. */
    public static List<Path> without(List<Path> jars) {
        return jars.stream()
                .filter(jar -> !PATHS.contains(jar.toAbsolutePath().normalize()))
                .toList();
    }

    /** Forgets every jar; for tests. */
    static void clear() {
        PATHS.clear();
    }
}
