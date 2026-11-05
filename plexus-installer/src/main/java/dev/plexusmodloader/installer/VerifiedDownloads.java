package dev.plexusmodloader.installer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Downloads from Mojang's servers that are checked by the SHA-1 Mojang's own version data lists, and reads that
 * data. A file that is already there with the right SHA-1 is kept; anything else is downloaded to a partial file,
 * checked, and only then moved into place, so a failed or damaged download never leaves a file that looks complete.
 */
final class VerifiedDownloads {

    private final ServerInstaller.Downloader downloader;

    VerifiedDownloads(ServerInstaller.Downloader downloader) {
        this.downloader = downloader;
    }

    /**
     * Makes {@code target} the file at {@code uri}, checked by {@code sha1}.
     *
     * @return true when the file was downloaded, false when the file there already had that SHA-1
     * @throws InstallerException when the download fails or the file does not have {@code sha1}
     */
    boolean fetch(URI uri, String sha1, Path target) throws InstallerException, IOException {
        if (Files.isRegularFile(target) && sha1(target).equalsIgnoreCase(sha1)) {
            return false;
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path partial = Files.createTempFile(
                target.toAbsolutePath().getParent(), target.getFileName().toString(), ".partial");
        try {
            try {
                downloader.download(uri, partial);
            } catch (IOException e) {
                throw new InstallerException(
                        "Downloading " + target.getFileName() + " from " + uri + " failed: " + e.getMessage()
                                + ". Check the internet connection and try again.",
                        e);
            }
            String actual = sha1(partial);
            if (!actual.equalsIgnoreCase(sha1)) {
                throw new InstallerException("The download of " + target.getFileName() + " is damaged (SHA-1 " + actual
                        + ", expected " + sha1 + "). Try again.");
            }
            LauncherProfileInstaller.move(partial, target);
        } finally {
            Files.deleteIfExists(partial);
        }
        return true;
    }

    /** Downloads the {@code url} and {@code sha1} of one entry of Mojang's version data ({@code downloads.client}). */
    boolean fetch(JsonObject download, Path target) throws InstallerException, IOException {
        return fetch(URI.create(string(download, "url")), string(download, "sha1"), target);
    }

    /**
     * The version JSON of {@code minecraftVersion} from Mojang's manifest, kept at {@code versionFile} (checked by
     * the SHA-1 the manifest lists).
     */
    JsonObject versionJson(String minecraftVersion, Path versionFile) throws InstallerException, IOException {
        Path folder = versionFile.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path manifestFile = Files.createTempFile(folder, "version_manifest_v2.json", ".partial");
        JsonObject manifest;
        try {
            try {
                downloader.download(ServerInstaller.VERSION_MANIFEST, manifestFile);
            } catch (IOException e) {
                throw new InstallerException(
                        "Downloading Mojang's version list failed: " + e.getMessage()
                                + ". Check the internet connection and try again.",
                        e);
            }
            manifest = parse(manifestFile);
        } finally {
            Files.deleteIfExists(manifestFile);
        }
        JsonObject entry = null;
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            if (minecraftVersion.equals(string(element.getAsJsonObject(), "id"))) {
                entry = element.getAsJsonObject();
            }
        }
        if (entry == null) {
            throw new InstallerException("Mojang's version list has no Minecraft " + minecraftVersion + ".");
        }
        fetch(entry, versionFile);
        return parse(versionFile);
    }

    static JsonObject parse(Path file) throws InstallerException, IOException {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new InstallerException("Mojang's " + file.getFileName() + " could not be read: " + e.getMessage(), e);
        }
    }

    static JsonObject object(JsonObject parent, String key) throws InstallerException {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new InstallerException(
                    "Mojang's version data has no \"" + key + "\"; Plexus Mod Loader cannot use it.");
        }
        return value.getAsJsonObject();
    }

    static String string(JsonObject parent, String key) throws InstallerException {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            throw new InstallerException(
                    "Mojang's version data has no \"" + key + "\"; Plexus Mod Loader cannot use it.");
        }
        return value.getAsString();
    }

    static String sha1(Path file) throws IOException {
        return digest(file, "SHA-1");
    }

    static String digest(Path file, String algorithm) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has " + algorithm, e);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha1(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-1", e);
        }
    }
}
