package dev.plexusmodloader.compat.forge.modlauncher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.fml.loading.targets.CommonLaunchHandler.LocatedPaths;

/** What the launch handlers for Forge 47 (Minecraft 1.20.1) take from the system properties {@code ForgeGameHost} sets. */
final class Paths47 {

    /** System property with the jars FML runs as mods and language providers, separated as a class path is. */
    static final String MOD_FILES = "plexus.forge.modFiles";

    /** System property with the path of the patched game jar. */
    static final String GAME_JAR = "plexus.forge.patchedGame";

    /**
     * System property with the Forge mods Plexus Mod Loader found (the jars FML reads), separated as a class path is. FML
     * 47 does not scan the mods folder here and finds no mod as a resource, so they are handed over like the "forge" mod.
     */
    static final String MODS = "plexus.forge.mods";

    private Paths47() {}

    /**
     * The Minecraft mod is the whole patched jar; Forge's universal jar is the "forge" mod; FML's own jars (fmlcore and the
     * language providers) are the other artifacts. FML 47's ClasspathLocator reads only the system class path, so they
     * cannot be found as resources of the game class loader as in the later Forge builds.
     */
    static LocatedPaths locatedPaths() {
        return locatedPaths(
                System.getProperty(GAME_JAR), System.getProperty(MOD_FILES, ""), System.getProperty(MODS, ""));
    }

    static LocatedPaths locatedPaths(String gameJar, String modFiles) {
        return locatedPaths(gameJar, modFiles, "");
    }

    static LocatedPaths locatedPaths(String gameJar, String modFiles, String modJars) {
        if (gameJar == null) {
            throw new IllegalStateException("Plexus Mod Loader did not give the Forge launch the patched game jar ("
                    + GAME_JAR + " is not set)");
        }
        List<List<Path>> mods = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        for (String file : modFiles.split(java.io.File.pathSeparator)) {
            if (file.isEmpty()) {
                continue;
            }
            Path path = Path.of(file);
            if (path.getFileName().toString().contains("-universal")) {
                mods.add(List.of(path));
            } else {
                artifacts.add(path);
            }
        }
        for (String file : modJars.split(java.io.File.pathSeparator)) {
            if (!file.isEmpty()) {
                mods.add(List.of(Path.of(file)));
            }
        }
        return new LocatedPaths(List.of(Path.of(gameJar)), (path, base) -> true, mods, artifacts);
    }

    /**
     * The arguments without FML's own options ({@code --fml.forgeVersion 47.4.28} ...), which the launcher gives FML and
     * Minecraft's entry points do not know.
     */
    static String[] withoutFmlOptions(String[] args) {
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--fml.") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                i++;
            } else {
                kept.add(args[i]);
            }
        }
        return kept.toArray(String[]::new);
    }
}
