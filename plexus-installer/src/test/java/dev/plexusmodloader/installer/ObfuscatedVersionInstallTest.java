package dev.plexusmodloader.installer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Versions before 26.1 (obfuscated): the installer downloads Mojang's files, checks them, remaps the game jar on
 * "the player's machine" (a temporary folder) and points the profile or the server at the result.
 */
class ObfuscatedVersionInstallTest {

    private static final String VERSION = "1.21.11";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);
    private static final byte[] RUNTIME_JAR = "runtime bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LIBRARY_JAR = "library bytes".getBytes(StandardCharsets.UTF_8);
    private static final URI VERSION_URL = URI.create("https://piston-meta.example/v1/1.21.11.json");
    private static final URI CLIENT_URL = URI.create("https://piston-data.example/client.jar");
    private static final URI CLIENT_MAPPINGS_URL = URI.create("https://piston-data.example/client.txt");
    private static final URI SERVER_URL = URI.create("https://piston-data.example/server.jar");
    private static final URI SERVER_MAPPINGS_URL = URI.create("https://piston-data.example/server.txt");
    private static final URI LIBRARY_URL = URI.create("https://libraries.example/lib.jar");
    private static final URI NATIVE_URL = URI.create("https://libraries.example/lib-natives.jar");
    private static final URI OTHER_OS_URL = URI.create("https://libraries.example/other-os.jar");

    @TempDir
    Path folder;

    private final Map<URI, byte[]> responses = new HashMap<>();
    private final List<URI> downloaded = new ArrayList<>();
    private byte[] clientJar;

    @BeforeEach
    void mojang() throws IOException {
        clientJar = SyntheticGame.jar();
        byte[] mappings = SyntheticGame.MAPPINGS.getBytes(StandardCharsets.UTF_8);
        byte[] bundler = bundler(clientJar);
        String version = """
                {"id": "1.21.11", "javaVersion": {"component": "java-runtime-delta", "majorVersion": 21},
                 "downloads": {
                   "client": {"url": "%s", "sha1": "%s"},
                   "client_mappings": {"url": "%s", "sha1": "%s"},
                   "server": {"url": "%s", "sha1": "%s"},
                   "server_mappings": {"url": "%s", "sha1": "%s"}},
                 "libraries": [
                   {"name": "com.example:lib:1", "downloads": {"artifact": {"path": "com/example/lib/1/lib-1.jar",
                      "url": "%s", "sha1": "%s", "size": %d}}},
                   {"name": "com.example:lib:1:natives-windows", "downloads": {"artifact": {
                      "path": "com/example/lib/1/lib-1-natives-windows.jar", "url": "%s", "sha1": "%s", "size": 1}}},
                   {"name": "com.example:other-os:1", "rules": [{"action": "allow", "os": {"name": "plan9"}}],
                    "downloads": {"artifact": {"path": "com/example/other-os/1/other-os-1.jar", "url": "%s",
                      "sha1": "%s", "size": 1}}}]}
                """.formatted(
                        CLIENT_URL,
                        sha1(clientJar),
                        CLIENT_MAPPINGS_URL,
                        sha1(mappings),
                        SERVER_URL,
                        sha1(bundler),
                        SERVER_MAPPINGS_URL,
                        sha1(mappings),
                        LIBRARY_URL,
                        sha1(LIBRARY_JAR),
                        LIBRARY_JAR.length,
                        NATIVE_URL,
                        sha1(LIBRARY_JAR),
                        OTHER_OS_URL,
                        sha1(LIBRARY_JAR));
        byte[] versionBytes = version.getBytes(StandardCharsets.UTF_8);
        responses.put(
                ServerInstaller.VERSION_MANIFEST,
                ("{\"versions\": [{\"id\": \"1.21.11\", \"url\": \"" + VERSION_URL + "\", \"sha1\": \""
                                + sha1(versionBytes) + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8));
        responses.put(VERSION_URL, versionBytes);
        responses.put(CLIENT_URL, clientJar);
        responses.put(CLIENT_MAPPINGS_URL, mappings);
        responses.put(SERVER_URL, bundler);
        responses.put(SERVER_MAPPINGS_URL, mappings);
        responses.put(LIBRARY_URL, LIBRARY_JAR);
        // Libraries that must not be fetched: natives hold no classes the remap needs, and another OS's are not ours.
        responses.put(NATIVE_URL, LIBRARY_JAR);
        responses.put(OTHER_OS_URL, LIBRARY_JAR);
    }

    @Test
    void theProfileRunsOnTheRemappedClientJar() throws Exception {
        Path minecraft = launcherFolder();
        List<String> progress = new ArrayList<>();

        profileInstaller().install(minecraft, VERSION, true, JavaMemory.LAUNCHER_DEFAULT, progress::add);

        Path remappedFolder = minecraft.resolve("libraries/dev/plexusmodloader/minecraft-mojang/" + VERSION);
        List<Path> remapped;
        try (var files = Files.list(remappedFolder)) {
            remapped = files.filter(file -> file.getFileName().toString().endsWith(".jar"))
                    .toList();
        }
        assertEquals(1, remapped.size(), remapped.toString());
        String jarName = remapped.get(0).getFileName().toString();
        assertTrue(jarName.matches("minecraft-mojang-1\\.21\\.11-[0-9a-f]{8}\\.jar"), jarName);
        try (JarFile jar = new JarFile(remapped.get(0).toFile())) {
            assertNotNull(jar.getEntry("net/minecraft/world/Thing.class"));
            assertEquals(null, jar.getEntry("a.class"));
            assertNotNull(jar.getEntry("version.json"));
        }
        // Mojang's own files, checked by SHA-1, where the launcher keeps them; the mappings stay in this folder.
        assertArrayEquals(clientJar, Files.readAllBytes(minecraft.resolve("versions/1.21.11/1.21.11.jar")));
        assertTrue(Files.isRegularFile(minecraft.resolve("versions/1.21.11/1.21.11.json")));
        assertEquals(
                SyntheticGame.MAPPINGS,
                Files.readString(remappedFolder.resolve("client-mappings-" + VERSION + ".txt")));
        // The launcher downloads the libraries; the remap reads none (it keeps the jar's frames as they are).
        assertFalse(Files.exists(minecraft.resolve("libraries/com/example/lib/1/lib-1.jar")));
        assertFalse(downloaded.contains(NATIVE_URL), downloaded.toString());
        assertFalse(downloaded.contains(OTHER_OS_URL), downloaded.toString());

        JsonObject version =
                json(minecraft.resolve("versions/plexusmodloader-0.1.0-1.21.11/plexusmodloader-0.1.0-1.21.11.json"));
        assertEquals("1.21.11", version.get("inheritsFrom").getAsString());
        // The game jar the profile runs on, and the Java it needs.
        List<String> jvm = new ArrayList<>();
        version.getAsJsonObject("arguments").getAsJsonArray("jvm").forEach(argument -> jvm.add(argument.getAsString()));
        String libraryPath = "dev/plexusmodloader/minecraft-mojang/" + VERSION + "/" + jarName;
        assertTrue(jvm.contains("-Dplexus.gameJar=${library_directory}/" + libraryPath), jvm.toString());
        assertEquals(
                25, version.getAsJsonObject("javaVersion").get("majorVersion").getAsInt());
        JsonObject library = null;
        for (var element : version.getAsJsonArray("libraries")) {
            if (element.getAsJsonObject()
                    .get("name")
                    .getAsString()
                    .startsWith("dev.plexusmodloader:minecraft-mojang:")) {
                library = element.getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact");
            }
        }
        assertNotNull(library, "the remapped jar is a library of the version");
        assertEquals(libraryPath, library.get("path").getAsString());
        assertEquals(
                sha1(Files.readAllBytes(remapped.get(0))), library.get("sha1").getAsString());
        assertEquals(Files.size(remapped.get(0)), library.get("size").getAsLong());
        assertTrue(progress.stream().anyMatch(line -> line.startsWith("Remapping ")), progress.toString());
        assertTrue(Files.readString(minecraft.resolve("launcher_profiles.json")).contains("plexusmodloader-1.21.11"));
    }

    @Test
    void installingAgainTakesTheCachedJarAndDownloadsNothing() throws Exception {
        Path minecraft = launcherFolder();
        profileInstaller().install(minecraft, VERSION, true, JavaMemory.LAUNCHER_DEFAULT, line -> {});
        Path remapped = onlyRemappedJar(minecraft);
        byte[] before = Files.readAllBytes(remapped);
        downloaded.clear();
        List<String> progress = new ArrayList<>();

        profileInstaller().install(minecraft, VERSION, true, JavaMemory.LAUNCHER_DEFAULT, progress::add);

        assertEquals(List.of(), downloaded);
        assertTrue(progress.stream().anyMatch(line -> line.contains("is up to date")), progress.toString());
        assertTrue(progress.stream().noneMatch(line -> line.startsWith("Remapping ")), progress.toString());
        assertEquals(sha1(before), sha1(Files.readAllBytes(onlyRemappedJar(minecraft))));
    }

    @Test
    void aDamagedCachedJarIsMadeAgain() throws Exception {
        Path minecraft = launcherFolder();
        profileInstaller().install(minecraft, VERSION, true, JavaMemory.LAUNCHER_DEFAULT, line -> {});
        Path remapped = onlyRemappedJar(minecraft);
        byte[] good = Files.readAllBytes(remapped);
        Files.write(remapped, "cut off".getBytes(StandardCharsets.UTF_8));

        profileInstaller().install(minecraft, VERSION, true, JavaMemory.LAUNCHER_DEFAULT, line -> {});

        assertEquals(sha1(good), sha1(Files.readAllBytes(onlyRemappedJar(minecraft))));
    }

    @Test
    void aDamagedDownloadStopsTheInstallBeforeAnythingIsWritten() throws Exception {
        Path minecraft = launcherFolder();
        responses.put(CLIENT_MAPPINGS_URL, "cut off".getBytes(StandardCharsets.UTF_8));

        InstallerException problem = assertThrows(
                InstallerException.class,
                () -> profileInstaller().install(minecraft, VERSION, true, JavaMemory.LAUNCHER_DEFAULT, line -> {}));

        assertTrue(problem.getMessage().contains("client-mappings-1.21.11.txt is damaged"), problem.getMessage());
        assertFalse(Files.exists(minecraft.resolve("versions/plexusmodloader-0.1.0-1.21.11")));
        assertFalse(
                Files.readString(minecraft.resolve("launcher_profiles.json")).contains("plexusmodloader"));
        assertFalse(Files.exists(minecraft.resolve(
                "libraries/dev/plexusmodloader/minecraft-mojang/1.21.11/client-mappings-1.21.11.txt")));
    }

    @Test
    void aFailedDownloadIsReportedWithItsAddress() throws Exception {
        Path minecraft = launcherFolder();
        responses.remove(CLIENT_URL);

        InstallerException problem = assertThrows(
                InstallerException.class,
                () -> profileInstaller().install(minecraft, VERSION, true, JavaMemory.LAUNCHER_DEFAULT, line -> {}));

        assertTrue(
                problem.getMessage().contains("Downloading 1.21.11.jar from " + CLIENT_URL + " failed"),
                problem.getMessage());
        assertFalse(Files.exists(minecraft.resolve("versions/plexusmodloader-0.1.0-1.21.11")));
    }

    @Test
    void mappingsThatDoNotBelongToTheJarStopTheInstall() throws Exception {
        Path minecraft = launcherFolder();
        byte[] unrelated = SyntheticGame.UNRELATED_MAPPINGS.getBytes(StandardCharsets.UTF_8);
        responses.put(CLIENT_MAPPINGS_URL, unrelated);
        // The version JSON is the launcher's own copy; the mappings' SHA-1 in it is the unrelated file's.
        String version = new String(responses.get(VERSION_URL), StandardCharsets.UTF_8)
                .replaceFirst(
                        "(\"client_mappings\": \\{\"url\": \"[^\"]+\", \"sha1\": \")[0-9a-f]+", "$1" + sha1(unrelated));
        Files.createDirectories(minecraft.resolve("versions/1.21.11"));
        Files.writeString(minecraft.resolve("versions/1.21.11/1.21.11.json"), version);

        InstallerException problem = assertThrows(
                InstallerException.class,
                () -> profileInstaller().install(minecraft, VERSION, true, JavaMemory.LAUNCHER_DEFAULT, line -> {}));

        assertTrue(problem.getMessage().contains("failed its check"), problem.getMessage());
        assertTrue(problem.getMessage().contains("none of its 3 classes"), problem.getMessage());
        assertFalse(Files.exists(minecraft.resolve("versions/plexusmodloader-0.1.0-1.21.11")));
        try (var files = Files.list(minecraft.resolve("libraries/dev/plexusmodloader/minecraft-mojang/1.21.11"))) {
            assertEquals(
                    List.of(),
                    files.filter(file -> file.toString().endsWith(".jar")).toList());
        }
    }

    @Test
    void uninstallingRemovesTheRemappedJarAndTheMappingsButNotMinecraftsFiles() throws Exception {
        Path minecraft = launcherFolder();
        profileInstaller().install(minecraft, VERSION, true, JavaMemory.LAUNCHER_DEFAULT, line -> {});

        profileInstaller().uninstall(minecraft, VERSION, line -> {});

        assertFalse(Files.exists(minecraft.resolve("libraries/dev/plexusmodloader/minecraft-mojang")));
        assertFalse(Files.exists(minecraft.resolve("versions/plexusmodloader-0.1.0-1.21.11")));
        assertTrue(Files.isRegularFile(minecraft.resolve("versions/1.21.11/1.21.11.jar")));
    }

    @Test
    void versionsFrom26_1NeedNoDownloadsAtInstallTime() throws Exception {
        Path minecraft = launcherFolder();

        profileInstaller().install(minecraft, "26.2", true, JavaMemory.LAUNCHER_DEFAULT, line -> {});

        assertEquals(List.of(), downloaded);
        JsonObject version =
                json(minecraft.resolve("versions/plexusmodloader-0.1.0-26.2/plexusmodloader-0.1.0-26.2.json"));
        assertFalse(version.has("javaVersion"));
        assertFalse(version.toString().contains("plexus.gameJar"));
        assertFalse(Files.exists(minecraft.resolve("libraries/dev/plexusmodloader/minecraft-mojang")));
    }

    @Test
    void theServerRunsOnTheRemappedServerJar() throws Exception {
        Path server = folder.resolve("server");

        List<String> progress = new ArrayList<>();
        List<String> warnings = serverInstaller().install(server, VERSION, JavaMemory.LAUNCHER_DEFAULT, progress::add);

        assertEquals(List.of(), warnings);
        Path remapped = server.resolve("versions/1.21.11/server-1.21.11-mojang.jar");
        try (JarFile jar = new JarFile(remapped.toFile())) {
            assertNotNull(jar.getEntry("net/minecraft/world/Thing.class"));
            assertEquals(null, jar.getEntry("a.class"));
        }
        assertTrue(Files.isRegularFile(server.resolve("versions/1.21.11/server-mappings-1.21.11.txt")));
        try (JarFile launch =
                new JarFile(server.resolve(ServerInstaller.LAUNCH_JAR).toFile())) {
            String classPath = launch.getManifest().getMainAttributes().getValue("Class-Path");
            assertTrue(classPath.endsWith(" versions/1.21.11/server-1.21.11-mojang.jar"), classPath);
            assertFalse(classPath.contains("versions/1.21.11/server-1.21.11.jar"), classPath);
        }
        // Plexus Mod Loader runs on Java 25 whatever Java the vanilla version asks for (here 21).
        assertTrue(Files.readString(server.resolve("run.bat")).contains("Needs Java 25 or newer"));

        downloaded.clear();
        progress.clear();
        serverInstaller().install(server, VERSION, JavaMemory.LAUNCHER_DEFAULT, progress::add);
        assertEquals(List.of(ServerInstaller.VERSION_MANIFEST), downloaded);
        assertTrue(progress.stream().anyMatch(line -> line.contains("is up to date")), progress.toString());
    }

    @Test
    void aServerWithDamagedMappingsIsNotInstalled() throws Exception {
        Path server = folder.resolve("server");
        responses.put(SERVER_MAPPINGS_URL, "cut off".getBytes(StandardCharsets.UTF_8));

        InstallerException problem = assertThrows(
                InstallerException.class,
                () -> serverInstaller().install(server, VERSION, JavaMemory.LAUNCHER_DEFAULT, line -> {}));

        assertTrue(problem.getMessage().contains("server-mappings-1.21.11.txt is damaged"), problem.getMessage());
        assertFalse(Files.exists(server.resolve(ServerInstaller.LAUNCH_JAR)));
        assertFalse(Files.exists(server.resolve("versions/1.21.11/server-1.21.11-mojang.jar")));
    }

    @Test
    void theCommandLineReportsAFailedRemapWithExitCode1() throws Exception {
        Path minecraft = launcherFolder();
        responses.put(CLIENT_MAPPINGS_URL, "cut off".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        InstallerCommandLine commandLine = new InstallerCommandLine(
                payload(),
                new java.io.PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new java.io.PrintStream(err, true, StandardCharsets.UTF_8),
                downloader());

        assertEquals(1, commandLine.run(List.of("install", "--dir", minecraft.toString(), "--minecraft", VERSION)));
        assertTrue(err.toString(StandardCharsets.UTF_8)
                .contains("Error: The download of client-mappings-1.21.11.txt is damaged"));
    }

    private Path launcherFolder() throws IOException {
        Path minecraft = folder.resolve(".minecraft");
        Files.createDirectories(minecraft);
        Files.writeString(minecraft.resolve("launcher_profiles.json"), "{\"profiles\": {}}");
        return minecraft;
    }

    private Path onlyRemappedJar(Path minecraft) throws IOException {
        try (var files = Files.list(minecraft.resolve("libraries/dev/plexusmodloader/minecraft-mojang/" + VERSION))) {
            List<Path> jars =
                    files.filter(file -> file.toString().endsWith(".jar")).toList();
            assertEquals(1, jars.size(), jars.toString());
            return jars.get(0);
        }
    }

    private ServerInstaller.Downloader downloader() {
        return (uri, target) -> {
            byte[] body = responses.get(uri);
            if (body == null) {
                throw new IOException("HTTP 404 " + uri);
            }
            downloaded.add(uri);
            Files.write(target, body);
        };
    }

    private LauncherProfileInstaller profileInstaller() {
        return new LauncherProfileInstaller(payload(), CLOCK, downloader());
    }

    private ServerInstaller serverInstaller() {
        return new ServerInstaller(payload(), downloader());
    }

    private static InstallerPayload payload() {
        return new InstallerPayload(
                "0.1.0",
                List.of("1.21.11", "26.2"),
                "dev.plexusmodloader.bootstrap.PlexusLauncherMain",
                List.of(new InstallerPayload.Library(
                        "dev.plexusmodloader:plexus-runtime-impl:0.1.0",
                        "dev/plexusmodloader/plexus-runtime-impl/0.1.0/plexus-runtime-impl-0.1.0.jar",
                        sha1(RUNTIME_JAR),
                        RUNTIME_JAR.length)),
                path -> new ByteArrayInputStream(RUNTIME_JAR));
    }

    /** A server bundler as Mojang ships it, holding the obfuscated server jar and one library. */
    private static byte[] bundler(byte[] serverJar) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "META-INF/versions.list", sha256(serverJar) + "\t1.21.11\t1.21.11/server-1.21.11.jar\n");
            entry(zip, "META-INF/versions/1.21.11/server-1.21.11.jar", serverJar);
            entry(
                    zip,
                    "META-INF/libraries.list",
                    sha256(LIBRARY_JAR) + "\tcom.example:lib:1\tcom/example/lib/1/lib-1.jar\n");
            entry(zip, "META-INF/libraries/com/example/lib/1/lib-1.jar", LIBRARY_JAR);
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        entry(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static JsonObject json(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static String sha1(byte[] bytes) {
        return hash(bytes, "SHA-1");
    }

    private static String sha256(byte[] bytes) {
        return hash(bytes, "SHA-256");
    }

    private static String hash(byte[] bytes, String algorithm) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
