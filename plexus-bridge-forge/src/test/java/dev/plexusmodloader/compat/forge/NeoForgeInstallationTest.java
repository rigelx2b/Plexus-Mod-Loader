package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0026: NeoForge's own server starts with the libraries its installer writes into the server's class path ({@code
 * data/win_args.txt}), which include libraries of {@code install_profile.json} that {@code version.json} does not list:
 * LWJGL's core and tinyfd for NeoForge 26.1.x (ImmediatelyFast's 26.1 build reads its configuration with LWJGL's {@code
 * MathUtil} on the server), tinyfd for 26.2.
 */
class NeoForgeInstallationTest {

    @Test
    void theServerAlsoGetsTheInstallProfileLibrariesOnNeoForgesServerClassPath(@TempDir Path directory)
            throws IOException {
        Path installer = installer(directory.resolve("installer.jar"));

        try (JarFile jar = new JarFile(installer.toFile())) {
            List<String> names = NeoForgeInstallation.serverClassPathLibraries(jar).stream()
                    .map(library -> library.get("name").getAsString())
                    .toList();

            assertEquals(List.of("org.lwjgl:lwjgl-tinyfd:3.4.1", "org.lwjgl:lwjgl:3.4.1"), names);
        }
    }

    @Test
    void everySupportedNeoForgeVersionHasAWellFormedReleaseLine() {
        // A line "1.21.11=21.11.48<sha1>" without the space once took NeoForge away from 1.21.11 unnoticed.
        for (String minecraft : List.of("1.20.4", "1.21.1", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3")) {
            NeoForgeInstallation.Release release =
                    NeoForgeInstallation.release(minecraft).orElseThrow(() -> new AssertionError(minecraft));
            assertEquals(minecraft, release.minecraftVersion());
            org.junit.jupiter.api.Assertions.assertTrue(
                    release.neoForgeVersion().matches("\\d+\\.\\d+\\.\\d+(\\.\\d+)?(-beta)?"), release.toString());
            org.junit.jupiter.api.Assertions.assertTrue(
                    release.installerSha1().matches("[0-9a-f]{40}"), release.toString());
        }
    }

    @Test
    void aCoordinateIsComparedWithoutItsExtension() {
        // NeoForge 20.4's install_profile.json names its libraries "...:installertools:2.1.2@jar"; the processors do
        // not.
        assertEquals(
                "net.neoforged.installertools:installertools:2.1.2",
                NeoForgeInstallation.withoutExtension("net.neoforged.installertools:installertools:2.1.2@jar"));
        assertEquals(
                "net.neoforged:AutoRenamingTool:1.0.13:all",
                NeoForgeInstallation.withoutExtension("net.neoforged:AutoRenamingTool:1.0.13:all@jar"));
        assertEquals(
                "net.neoforged:neoforge:20.4.253:universal",
                NeoForgeInstallation.withoutExtension("net.neoforged:neoforge:20.4.253:universal"));
    }

    @Test
    void theCoordinatesOfTheProfileAreFilesOfTheLibrariesFolder() {
        Path libraries = Path.of("libraries");

        assertEquals(
                libraries.resolve("net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar"),
                NeoForgeInstallation.coordinatePath(libraries, "[net.minecraft:server:1.21.1-20240808.144430:srg]"));
        assertEquals(
                libraries.resolve("net/neoforged/neoform/1.21.1-20240808.144430/neoform-1.21.1-20240808.144430.zip"),
                NeoForgeInstallation.coordinatePath(libraries, "[net.neoforged:neoform:1.21.1-20240808.144430@zip]"));
    }

    @Test
    void theGameIsLaidOutWhereFml4LooksForIt(@TempDir Path directory) throws IOException {
        Path libraries = directory.resolve("libraries");
        Path patched = libraries.resolve("net/neoforged/minecraft-server-patched/21.1.265/patched.jar");
        Files.createDirectories(patched.getParent());
        Files.writeString(patched, "patched game");
        JsonObject profile = com.google.gson.JsonParser.parseString("""
                {"data": {
                  "MC_SRG": {"client": "[net.minecraft:client:1.21.1-1:srg]", "server": "[net.minecraft:server:1.21.1-1:srg]"},
                  "MC_EXTRA": {"client": "[net.minecraft:client:1.21.1-1:extra]", "server": "[net.minecraft:server:1.21.1-1:extra]"},
                  "PATCHED": {"client": "[net.neoforged:neoforge:21.1.265:client]", "server": "[net.neoforged:neoforge:21.1.265:server]"}
                }}
                """).getAsJsonObject();

        NeoForgeInstallation.layOutForFml4(profile, "server", patched, libraries);
        NeoForgeInstallation.layOutForFml4(profile, "server", patched, libraries);

        assertEquals(
                "patched game",
                Files.readString(libraries.resolve("net/minecraft/server/1.21.1-1/server-1.21.1-1-srg.jar")));
        for (String empty : List.of(
                "net/minecraft/server/1.21.1-1/server-1.21.1-1-extra.jar",
                "net/neoforged/neoforge/21.1.265/neoforge-21.1.265-server.jar")) {
            try (JarFile jar = new JarFile(libraries.resolve(empty).toFile())) {
                assertEquals(1, jar.stream().count(), empty);
            }
        }
    }

    @Test
    void anInstallerWithoutServerArgumentsAddsNothing(@TempDir Path directory) throws IOException {
        Path installer = directory.resolve("installer.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(installer))) {
            put(out, "install_profile.json", "{\"libraries\": []}");
        }

        try (JarFile jar = new JarFile(installer.toFile())) {
            assertEquals(List.of(), NeoForgeInstallation.serverClassPathLibraries(jar));
        }
    }

    /**
     * An installer like NeoForge 26.1.2.122's: {@code version.json} lists FML's loader; {@code install_profile.json}
     * lists the universal jar, an installer tool, tinyfd and LWJGL; the server's class path names FML's loader, tinyfd,
     * LWJGL and a library of Minecraft's own server.
     */
    private static Path installer(Path file) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            put(
                    out,
                    "version.json",
                    "{\"libraries\": ["
                            + library(
                                    "net.neoforged.fancymodloader:loader:11.0.15",
                                    "net/neoforged/fancymodloader/loader/11.0.15/loader-11.0.15.jar")
                            + "]}");
            put(
                    out,
                    "install_profile.json",
                    "{\"libraries\": ["
                            + library(
                                    "net.neoforged:neoforge:26.1.2.122:universal",
                                    "net/neoforged/neoforge/26.1.2.122/neoforge-26.1.2.122-universal.jar")
                            + ", "
                            + library(
                                    "net.neoforged.installertools:installertools:4.0.12",
                                    "net/neoforged/installertools/installertools/4.0.12/installertools-4.0.12.jar")
                            + ", "
                            + library(
                                    "org.lwjgl:lwjgl-tinyfd:3.4.1",
                                    "org/lwjgl/lwjgl-tinyfd/3.4.1/lwjgl-tinyfd-3.4.1.jar")
                            + ", " + library("org.lwjgl:lwjgl:3.4.1", "org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1.jar")
                            + "]}");
            put(
                    out,
                    "data/win_args.txt",
                    String.join(
                            "\n",
                            "--add-opens java.base/java.lang.invoke=ALL-UNNAMED",
                            "-DlibraryDirectory=libraries",
                            "-classpath",
                            String.join(
                                    ";",
                                    "libraries/net/neoforged/fancymodloader/loader/11.0.15/loader-11.0.15.jar",
                                    "libraries/org/lwjgl/lwjgl-tinyfd/3.4.1/lwjgl-tinyfd-3.4.1.jar",
                                    "libraries/org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1.jar",
                                    "libraries/io/netty/netty-common/4.2.15.Final/netty-common-4.2.15.Final.jar"),
                            "net.neoforged.fml.startup.Server"));
        }
        return file;
    }

    private static String library(String name, String path) {
        JsonObject artifact = new JsonObject();
        artifact.addProperty("path", path);
        artifact.addProperty("sha1", "0".repeat(40));
        artifact.addProperty("url", "https://maven.neoforged.net/releases/" + path);
        JsonObject downloads = new JsonObject();
        downloads.add("artifact", artifact);
        JsonObject library = new JsonObject();
        library.addProperty("name", name);
        library.add("downloads", downloads);
        return library.toString();
    }

    private static void put(JarOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
