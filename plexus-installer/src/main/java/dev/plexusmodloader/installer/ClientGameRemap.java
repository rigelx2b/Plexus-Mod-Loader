package dev.plexusmodloader.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Prepares the game jar of an obfuscated Minecraft version (before 26.1, ADR-0019) for a launcher profile, on the
 * player's machine:
 *
 * <ol>
 *   <li>the vanilla version JSON (kept if the launcher already has it) and the client jar, checked by the SHA-1 Mojang
 *       lists, in {@code versions/<minecraft>/} where the launcher keeps them
 *   <li>Mojang's client mappings ({@code downloads.client_mappings}), checked by SHA-1
 *   <li>the client libraries of this OS, which the launcher downloads anyway, to compute the remapped jar's frames
 *   <li>the remapped jar ({@link RemappedGameJar}), cached by jar SHA-1, mappings SHA-1 and remapper revision
 * </ol>
 *
 * <p>Mappings and remapped jar go to {@code libraries/dev/plexusmodloader/minecraft-mojang/<minecraft>/} of the
 * launcher folder and nowhere else (ADR-0019 point 3). The profile's version lists the remapped jar as a library and
 * points {@code plexus.gameJar} at it, so the game runs on it and not on the vanilla jar.
 */
final class ClientGameRemap {

    /** Below {@code libraries/}. */
    static final String FOLDER = "dev/plexusmodloader/minecraft-mojang";

    /** The Java the Plexus Mod Loader runtime needs, which the profile asks the launcher for instead of the vanilla one. */
    static final int RUNTIME_JAVA = 25;

    /**
     * The prepared jar.
     *
     * @param libraryPath its path below {@code libraries/}
     * @param libraryName the library name for the version JSON
     * @param jar the remapped jar
     */
    record Prepared(String libraryPath, String libraryName, RemappedGameJar jar) {}

    private ClientGameRemap() {}

    /** Whether {@code minecraftVersion} is obfuscated and so needs {@link #prepare}: every {@code 1.x}. */
    static boolean isObfuscated(String minecraftVersion) {
        int dot = minecraftVersion.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? minecraftVersion : minecraftVersion.substring(0, dot)) < 26;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static Prepared prepare(
            VerifiedDownloads downloads, Path minecraftDirectory, String minecraftVersion, Consumer<String> progress)
            throws InstallerException, IOException {
        Path versionDirectory = minecraftDirectory.resolve("versions").resolve(minecraftVersion);
        Path versionFile = versionDirectory.resolve(minecraftVersion + ".json");
        JsonObject version;
        if (Files.isRegularFile(versionFile)) {
            version = VerifiedDownloads.parse(versionFile);
        } else {
            progress.accept("Downloading the version data of Minecraft " + minecraftVersion);
            version = downloads.versionJson(minecraftVersion, versionFile);
        }
        JsonObject offered = VerifiedDownloads.object(version, "downloads");
        if (!offered.has("client_mappings")) {
            throw new InstallerException("Mojang publishes no mappings for the Minecraft " + minecraftVersion
                    + " client, so Plexus Mod Loader cannot remap it to Mojang names.");
        }

        Path clientJar = versionDirectory.resolve(minecraftVersion + ".jar");
        JsonObject client = VerifiedDownloads.object(offered, "client");
        JsonObject clientMappings = VerifiedDownloads.object(offered, "client_mappings");
        if (downloads.fetch(client, clientJar)) {
            progress.accept("Downloaded the Minecraft " + minecraftVersion + " client");
        }
        Path folder = minecraftDirectory
                .resolve("libraries")
                .resolve(FOLDER)
                .resolve(minecraftVersion)
                .toAbsolutePath()
                .normalize();
        Path mappings = folder.resolve("client-mappings-" + minecraftVersion + ".txt");
        if (downloads.fetch(clientMappings, mappings)) {
            progress.accept(
                    "Downloaded Mojang's client mappings for " + minecraftVersion + " (kept in " + folder + ")");
        }
        List<Path> libraries = libraries(downloads, version, minecraftDirectory.resolve("libraries"), progress);

        // Both files were just checked against the SHA-1 Mojang lists, so those values are their SHA-1: the key needs
        // no further pass over the jar and the mappings.
        String key = RemappedGameJar.key(listedSha1(client), listedSha1(clientMappings));
        String stem = "minecraft-mojang-" + minecraftVersion + "-" + RemappedGameJar.shortKey(key);
        RemappedGameJar jar =
                RemappedGameJar.ensure(clientJar, mappings, key, libraries, folder.resolve(stem + ".jar"), progress);
        if (jar.reused()) {
            progress.accept("The remapped Minecraft " + minecraftVersion + " client is up to date");
        }
        removeOlder(folder, minecraftVersion, stem);
        return new Prepared(
                FOLDER + "/" + minecraftVersion + "/" + stem + ".jar",
                "dev.plexusmodloader:minecraft-mojang:" + minecraftVersion + "-" + RemappedGameJar.shortKey(key),
                jar);
    }

    /** The SHA-1 of one entry of Mojang's version data, in the lower case {@link VerifiedDownloads#sha1} writes. */
    static String listedSha1(JsonObject download) throws InstallerException {
        return VerifiedDownloads.string(download, "sha1").toLowerCase(Locale.ROOT);
    }

    /** Removes the whole folder of {@code minecraftVersion}: mappings and every remapped jar. */
    static void remove(Path minecraftDirectory, String minecraftVersion) throws IOException {
        Path libraries = minecraftDirectory.resolve("libraries");
        Path folder = libraries.resolve(FOLDER).resolve(minecraftVersion);
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                Files.delete(file);
            }
        }
        Files.delete(folder);
        for (Path parent = folder.getParent();
                parent != null && parent.startsWith(libraries) && !parent.equals(libraries);
                parent = parent.getParent()) {
            try (DirectoryStream<Path> rest = Files.newDirectoryStream(parent)) {
                if (rest.iterator().hasNext()) {
                    return;
                }
            }
            Files.delete(parent);
        }
    }

    /**
     * The client libraries this OS uses, downloaded where the launcher keeps them. Native libraries are left out: they
     * hold no classes the remap needs, and the launcher gets them itself.
     */
    private static List<Path> libraries(
            VerifiedDownloads downloads, JsonObject version, Path librariesDirectory, Consumer<String> progress)
            throws InstallerException, IOException {
        List<Path> paths = new ArrayList<>();
        JsonElement listed = version.get("libraries");
        if (listed == null || !listed.isJsonArray()) {
            return paths;
        }
        int downloaded = 0;
        for (JsonElement element : listed.getAsJsonArray()) {
            JsonObject library = element.getAsJsonObject();
            if (!allows(library.has("rules") ? library.getAsJsonArray("rules") : null) || isNative(library)) {
                continue;
            }
            JsonObject libraryDownloads = library.has("downloads") ? library.getAsJsonObject("downloads") : null;
            JsonObject artifact = libraryDownloads != null && libraryDownloads.has("artifact")
                    ? libraryDownloads.getAsJsonObject("artifact")
                    : null;
            if (artifact == null) {
                continue;
            }
            Path target = librariesDirectory
                    .resolve(VerifiedDownloads.string(artifact, "path"))
                    .toAbsolutePath()
                    .normalize();
            if (!target.startsWith(librariesDirectory.toAbsolutePath().normalize())) {
                throw new InstallerException("Mojang's version data names a library path that leaves the libraries"
                        + " folder: " + artifact.get("path"));
            }
            if (downloads.fetch(artifact, target)) {
                downloaded++;
            }
            paths.add(target);
        }
        if (downloaded > 0) {
            progress.accept("Downloaded " + downloaded + " Minecraft libraries");
        }
        return paths;
    }

    private static boolean isNative(JsonObject library) {
        String[] name = library.has("name") ? library.get("name").getAsString().split(":") : new String[0];
        return name.length > 3 && name[3].startsWith("natives");
    }

    /** The {@code rules} of Mojang's version data as the launcher reads them: the last matching rule decides. */
    static boolean allows(JsonArray rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            if (matches(rule)) {
                allowed = "allow".equals(rule.get("action").getAsString());
            }
        }
        return allowed;
    }

    private static boolean matches(JsonObject rule) {
        if (rule.has("features")) {
            return false;
        }
        if (rule.has("os")) {
            JsonObject os = rule.getAsJsonObject("os");
            if (os.has("name") && !os.get("name").getAsString().equals(osName())) {
                return false;
            }
            if (os.has("arch") && os.get("arch").getAsString().equals("x86")) {
                return System.getProperty("os.arch").equals("x86");
            }
        }
        return true;
    }

    private static String osName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        return os.contains("mac") ? "osx" : "linux";
    }

    /** Deletes remapped jars and keys of {@code minecraftVersion} made from other inputs than {@code currentStem}. */
    private static void removeOlder(Path folder, String minecraftVersion, String currentStem) throws IOException {
        String prefix = "minecraft-mojang-" + minecraftVersion + "-";
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, prefix + "*")) {
            for (Path file : files) {
                if (!file.getFileName().toString().startsWith(currentStem + ".")) {
                    Files.delete(file);
                }
            }
        }
    }
}
