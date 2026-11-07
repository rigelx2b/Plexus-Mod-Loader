package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.runtime.GameSide;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0038: a host whose patches check the loader's own renamed jar starts from the obfuscated jar and Mojang's
 * mappings that Plexus Mod Loader's remapped jar was made from. They are found by the layouts of a development run, a
 * dedicated server and a launcher profile, and only when the remapped jar's {@code .key} names the obfuscated jar.
 */
class ObfuscatedGameFilesTest {

    private static final String VERSION = "1.21.11";

    @Test
    void aDevelopmentClientIsFoundNextToItsRemappedJar(@TempDir Path folder) throws IOException {
        Path remapped = folder.resolve("client-1.21.11-mojang.jar");
        Path jar = write(folder.resolve("client-1.21.11.jar"), "obfuscated client");
        Path mappings = write(folder.resolve("client-mappings-1.21.11.txt"), "mappings");
        key(remapped, jar);

        assertEquals(
                Optional.of(new ObfuscatedGameFiles(jar, mappings)),
                ObfuscatedGameFiles.locate(remapped, GameSide.CLIENT, VERSION));
    }

    @Test
    void aDevelopmentServerIsFoundWhereTheBundlerWasUnpacked(@TempDir Path folder) throws IOException {
        Path remapped = folder.resolve("server-1.21.11-mojang.jar");
        Path jar = write(folder.resolve("server/versions/1.21.11/server-1.21.11.jar"), "obfuscated server");
        Path mappings = write(folder.resolve("server-mappings-1.21.11.txt"), "mappings");
        key(remapped, jar);

        assertEquals(
                Optional.of(new ObfuscatedGameFiles(jar, mappings)),
                ObfuscatedGameFiles.locate(remapped, GameSide.DEDICATED_SERVER, VERSION));
    }

    @Test
    void aLauncherProfileClientUsesTheLaunchersOwnJar(@TempDir Path launcher) throws IOException {
        Path folder = launcher.resolve("libraries/dev/plexusmodloader/minecraft-mojang/1.21.11");
        Path remapped = folder.resolve("minecraft-mojang-1.21.11-abcd1234.jar");
        Path jar = write(launcher.resolve("versions/1.21.11/1.21.11.jar"), "obfuscated client");
        Path mappings = write(folder.resolve("client-mappings-1.21.11.txt"), "mappings");
        key(remapped, jar);

        assertEquals(
                Optional.of(new ObfuscatedGameFiles(jar, mappings)),
                ObfuscatedGameFiles.locate(remapped, GameSide.CLIENT, VERSION));
    }

    @Test
    void aJarTheKeyDoesNotNameIsNotUsed(@TempDir Path folder) throws IOException {
        Path remapped = folder.resolve("client-1.21.11-mojang.jar");
        write(folder.resolve("client-1.21.11.jar"), "another jar than the key names");
        write(folder.resolve("client-mappings-1.21.11.txt"), "mappings");
        Files.writeString(
                folder.resolve("client-1.21.11-mojang.key"),
                "jar=0000000000000000000000000000000000000000\nmappings=x\n",
                StandardCharsets.UTF_8);

        assertTrue(
                ObfuscatedGameFiles.locate(remapped, GameSide.CLIENT, VERSION).isEmpty());
    }

    @Test
    void withoutAKeyFileOrInAnUnknownLayoutNothingIsFound(@TempDir Path folder) throws IOException {
        Path remapped = folder.resolve("client-1.21.11-mojang.jar");
        write(folder.resolve("client-1.21.11.jar"), "obfuscated client");
        write(folder.resolve("client-mappings-1.21.11.txt"), "mappings");

        assertTrue(
                ObfuscatedGameFiles.locate(remapped, GameSide.CLIENT, VERSION).isEmpty());
        assertTrue(ObfuscatedGameFiles.locate(folder.resolve("somewhere-else.jar"), GameSide.CLIENT, VERSION)
                .isEmpty());
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** The key file Plexus Mod Loader's remapper writes next to the remapped jar. */
    private static void key(Path remapped, Path obfuscated) throws IOException {
        String name = remapped.getFileName().toString();
        Path key = remapped.resolveSibling(name.substring(0, name.length() - ".jar".length()) + ".key");
        Files.writeString(
                key, "jar=" + ForgeInstallation.sha1(obfuscated) + "\nmappings=unused\n", StandardCharsets.UTF_8);
    }
}
