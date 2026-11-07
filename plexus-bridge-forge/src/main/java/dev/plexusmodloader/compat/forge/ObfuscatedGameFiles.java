package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.api.runtime.GameSide;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The obfuscated game jar and Mojang's mappings that a game jar remapped to Mojang names was made from (ADR-0019,
 * ADR-0038). A host whose patches apply to the loader's own renamed jar (NeoForge 21.11, {@link
 * NeoForgeInstallerTools}) cannot use Plexus Mod Loader's remapped jar, whose bytes differ from the loader's: it starts
 * from the same obfuscated jar and mappings.
 *
 * <p>The files are found by the layouts Plexus Mod Loader writes them in, and a jar is used only if the {@code .key}
 * file next to the remapped jar still names its SHA-1:
 *
 * <ul>
 *   <li>a development run or a dedicated server: {@code <folder>/<side>-<version>-mojang.jar} next to {@code
 *       <side>-<version>.jar} and {@code <side>-mappings-<version>.txt};
 *   <li>a launcher profile (client): {@code <launcher>/libraries/dev/plexusmodloader/minecraft-mojang/<version>/}
 *       holds the remapped jar and {@code client-mappings-<version>.txt}, and the launcher's own jar is {@code
 *       <launcher>/versions/<version>/<version>.jar}.
 * </ul>
 *
 * @param jar the obfuscated game jar
 * @param mappings Mojang's mappings for it (ProGuard format)
 */
record ObfuscatedGameFiles(Path jar, Path mappings) {

    /** Below {@code libraries/} in a launcher folder ({@code ClientGameRemap.FOLDER} of the installer). */
    private static final String[] PROFILE_FOLDER = {"dev", "plexusmodloader", "minecraft-mojang"};

    /**
     * Finds the files {@code remappedJar} was made from.
     *
     * @param remappedJar the game jar Plexus Mod Loader runs on (the one remapped to Mojang names)
     * @return the files, or empty when no known layout matches, a file is missing or the key names another jar
     */
    static Optional<ObfuscatedGameFiles> locate(Path remappedJar, GameSide side, String minecraftVersion)
            throws IOException {
        Path remapped = remappedJar.toAbsolutePath().normalize();
        Path folder = remapped.getParent();
        if (folder == null) {
            return Optional.empty();
        }
        String sideName = side == GameSide.CLIENT ? "client" : "server";
        List<Path> jars;
        Path mappings;
        if (remapped.getFileName().toString().equals(sideName + "-" + minecraftVersion + "-mojang.jar")) {
            jars = List.of(
                    folder.resolve(sideName + "-" + minecraftVersion + ".jar"),
                    // A development run unpacks Mojang's server bundler below the version folder.
                    folder.resolve("server")
                            .resolve("versions")
                            .resolve(minecraftVersion)
                            .resolve("server-" + minecraftVersion + ".jar"));
            mappings = folder.resolve(sideName + "-mappings-" + minecraftVersion + ".txt");
        } else {
            Path launcher = launcherFolder(folder, minecraftVersion);
            if (side != GameSide.CLIENT || launcher == null) {
                return Optional.empty();
            }
            jars = List.of(
                    launcher.resolve("versions").resolve(minecraftVersion).resolve(minecraftVersion + ".jar"));
            mappings = folder.resolve("client-mappings-" + minecraftVersion + ".txt");
        }
        if (!Files.isRegularFile(mappings)) {
            return Optional.empty();
        }
        for (Path jar : jars) {
            if (Files.isRegularFile(jar) && keyMatches(remapped, jar)) {
                return Optional.of(new ObfuscatedGameFiles(jar, mappings));
            }
        }
        return Optional.empty();
    }

    /** The launcher folder when {@code folder} is {@code <launcher>/libraries/dev/plexusmodloader/minecraft-mojang/<version>}. */
    private static Path launcherFolder(Path folder, String minecraftVersion) {
        if (!folder.getFileName().toString().equals(minecraftVersion)) {
            return null;
        }
        Path current = folder.getParent();
        for (int i = PROFILE_FOLDER.length - 1; i >= 0; i--) {
            if (current == null || !current.getFileName().toString().equals(PROFILE_FOLDER[i])) {
                return null;
            }
            current = current.getParent();
        }
        if (current == null || !current.getFileName().toString().equals("libraries")) {
            return null;
        }
        return current.getParent();
    }

    /**
     * Whether the key file next to {@code remapped} ({@code jar=<SHA-1>} on its first line) names {@code obfuscated}.
     * Without a key file the jars cannot be matched, so they do not.
     */
    static boolean keyMatches(Path remapped, Path obfuscated) throws IOException {
        String name = remapped.getFileName().toString();
        Path key = remapped.resolveSibling(name.substring(0, name.length() - ".jar".length()) + ".key");
        if (!Files.isRegularFile(key)) {
            return false;
        }
        for (String line : Files.readAllLines(key, StandardCharsets.UTF_8)) {
            if (line.startsWith("jar=")) {
                return line.substring("jar=".length()).strip().equals(ForgeInstallation.sha1(obfuscated));
            }
        }
        return false;
    }
}
