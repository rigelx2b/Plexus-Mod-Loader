package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

/** What Forge 47 (Minecraft 1.20.1) needs beyond the later builds (ADR-0038), checked on the shapes of its own files. */
class Forge47SupportTest {

    @Test
    void forgeBuildsBefore49AreTheOlderFamily() {
        assertTrue(ForgeGameHost.isOlderThan49("1.20.1-47.4.28"));
        assertFalse(ForgeGameHost.isOlderThan49("1.20.4-49.2.13"));
        assertFalse(ForgeGameHost.isOlderThan49("1.21.1-52.1.21"));
        assertFalse(ForgeGameHost.isOlderThan49("26.2-65.1.5"));
    }

    @Test
    void launchTargetsFollowTheForgeFamily() {
        assertEquals("plexusmodloader_client", ForgeGameHost.launchTarget("client", "1.20.1-47.4.28"));
        assertEquals("plexusmodloader_server", ForgeGameHost.launchTarget("server", "1.20.1-47.4.28"));
        assertEquals(
                "plexusmodloader_server_gametest", ForgeGameHost.launchTarget("game_test_server", "1.20.1-47.4.28"));
        assertEquals("forge_client", ForgeGameHost.launchTarget("client", "1.21.1-52.1.21"));
        assertEquals("forge_server", ForgeGameHost.launchTarget("server", "1.21.1-52.1.21"));
        assertEquals("plexusmodloader_server_gametest", ForgeGameHost.launchTarget("game_test_server", "26.2-65.1.5"));
    }

    @Test
    void theLaunchHandlersFollowTheModLauncherOfTheRelease() {
        assertEquals(
                List.of(
                        "dev.plexusmodloader.compat.forge.modlauncher.GameTestLaunchHandler47",
                        "dev.plexusmodloader.compat.forge.modlauncher.ServerLaunchHandler47",
                        "dev.plexusmodloader.compat.forge.modlauncher.ClientLaunchHandler47"),
                ForgeBootModules.launchHandlers(
                        Path.of("libraries/cpw/mods/modlauncher/10.0.9/modlauncher-10.0.9.jar")));
        assertEquals(
                List.of("dev.plexusmodloader.compat.forge.modlauncher.GameTestLaunchHandler"),
                ForgeBootModules.launchHandlers(
                        Path.of("libraries/net/minecraftforge/modlauncher/10.1.2/modlauncher-10.1.2.jar")));
    }

    @Test
    void theFmlOptionsOfTheProfileAreFoundInPairs() {
        JsonObject version = JsonParser.parseString("""
                {"arguments": {"game": ["--launchTarget", "forgeclient", "--fml.forgeVersion", "47.4.28",
                  "--fml.mcVersion", "1.20.1", "--fml.forgeGroup", "net.minecraftforge",
                  "--fml.mcpVersion", "20230612.114412"]}}
                """).getAsJsonObject();

        assertEquals(
                List.of(
                        "--fml.forgeVersion", "47.4.28",
                        "--fml.mcVersion", "1.20.1",
                        "--fml.forgeGroup", "net.minecraftforge",
                        "--fml.mcpVersion", "20230612.114412"),
                ForgeInstallation.fmlArguments(version));
        assertEquals(
                List.of(),
                ForgeInstallation.fmlArguments(JsonParser.parseString("{}").getAsJsonObject()));
    }

    @Test
    void theFileSystemJarsMayBeSecureJarHandlerOrSecureModules() {
        assertTrue(ForgeFileSystems.ARTIFACTS.contains("cpw.mods:securejarhandler"));
        assertTrue(ForgeFileSystems.OPTIONAL.containsAll(ForgeFileSystems.SECURE_JARS));
    }

    @Test
    void aReleaseWithNeitherSecureJarIsRefused(@TempDir Path libraries) {
        JsonObject version = JsonParser.parseString("""
                {"libraries": [
                  {"name": "net.minecraftforge:unsafe:0.2.0",
                   "downloads": {"artifact": {"path": "net/minecraftforge/unsafe/0.2.0/unsafe-0.2.0.jar"}}},
                  {"name": "net.minecraftforge:JarJarFileSystems:0.3.19",
                   "downloads": {"artifact": {"path": "net/minecraftforge/JarJarFileSystems/0.3.19/j.jar"}}}
                ]}
                """).getAsJsonObject();

        assertThrows(IOException.class, () -> ForgeInstallation.fileSystemJars(version, libraries));
    }

    @Test
    void securejarhandlerSatisfiesTheFileSystemCheck(@TempDir Path libraries) throws IOException {
        JsonObject version = JsonParser.parseString("""
                {"libraries": [
                  {"name": "cpw.mods:securejarhandler:2.1.10",
                   "downloads": {"artifact": {"path": "cpw/mods/securejarhandler/2.1.10/securejarhandler-2.1.10.jar"}}},
                  {"name": "net.minecraftforge:unsafe:0.2.0",
                   "downloads": {"artifact": {"path": "net/minecraftforge/unsafe/0.2.0/unsafe-0.2.0.jar"}}},
                  {"name": "net.minecraftforge:JarJarFileSystems:0.3.19",
                   "downloads": {"artifact": {"path": "net/minecraftforge/JarJarFileSystems/0.3.19/j.jar"}}}
                ]}
                """).getAsJsonObject();

        List<Path> jars = ForgeInstallation.fileSystemJars(version, libraries);

        assertEquals(3, jars.size());
        assertTrue(jars.getFirst().endsWith("securejarhandler-2.1.10.jar"));
    }

    @Test
    void theExtraFilesOfASplitGameJarJoinTheRenamedClasses(@TempDir Path directory) throws IOException {
        Path renamed = directory.resolve("renamed.jar");
        Path extra = directory.resolve("extra.jar");
        jar(renamed, "net/minecraft/Foo.class", "classes", "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
        jar(
                extra,
                "assets/minecraft/lang/en_us.json",
                "{}",
                "META-INF/MOJANG_C.SF",
                "signature",
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 2.0\n",
                "net/minecraft/Foo.class",
                "obfuscated");

        ForgeInstallerTools.addMissingEntries(renamed, extra);

        try (JarFile merged = new JarFile(renamed.toFile())) {
            assertNotNull(merged.getEntry("assets/minecraft/lang/en_us.json"));
            assertNull(merged.getEntry("META-INF/MOJANG_C.SF"));
            assertEquals(
                    "classes",
                    new String(
                            merged.getInputStream(merged.getEntry("net/minecraft/Foo.class"))
                                    .readAllBytes(),
                            StandardCharsets.UTF_8));
            assertEquals(
                    "Manifest-Version: 1.0\n",
                    new String(
                            merged.getInputStream(merged.getEntry("META-INF/MANIFEST.MF"))
                                    .readAllBytes(),
                            StandardCharsets.UTF_8));
        }
    }

    private static void jar(Path file, String... namesAndContents) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new JarEntry(namesAndContents[i]));
                out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }
}
