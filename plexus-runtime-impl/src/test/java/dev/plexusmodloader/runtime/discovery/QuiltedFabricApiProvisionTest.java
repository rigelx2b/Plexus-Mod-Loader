package dev.plexusmodloader.runtime.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuiltedFabricApiProvisionTest {

    @TempDir
    Path folder;

    @BeforeEach
    void turnOn() {
        System.setProperty("plexus.qsl", "on");
    }

    @AfterEach
    void reset() {
        System.clearProperty("plexus.qsl");
    }

    private static byte[] jarBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private DiscoveredModFile quiltMod(String id, String depends, Map<String, byte[]> nested) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(
                "quilt.mod.json",
                ("{\"schema_version\":1,\"quilt_loader\":{\"group\":\"g\",\"id\":\"" + id
                                + "\",\"version\":\"1\",\"depends\":[" + depends + "]}}")
                        .getBytes(StandardCharsets.UTF_8));
        entries.putAll(nested);
        Path file = folder.resolve(id + ".jar");
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(jarBytes(entries));
        }
        return new DiscoveredModFile(file, ModFileKind.QUILT, Optional.empty());
    }

    private DiscoveredModFile fabricApi() throws IOException {
        Path file = folder.resolve("fabric-api.jar");
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(jarBytes(Map.of(
                    "fabric.mod.json",
                    "{\"schemaVersion\":1,\"id\":\"fabric-api\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8))));
        }
        return new DiscoveredModFile(file, ModFileKind.FABRIC, Optional.empty());
    }

    private static String sha512(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
    }

    private QuiltedFabricApiProvision provision(byte[] content, AtomicInteger downloads, List<String> status)
            throws Exception {
        QuiltedFabricApiProvision.Release release = new QuiltedFabricApiProvision.Release(
                "1.20.1",
                "7.7.0+0.92.2-1.20.1",
                URI.create("https://cdn.modrinth.com/data/qvIfYCYJ/versions/x/qfapi-7.7.0_mc-1.20.1.jar"),
                sha512(content));
        return new QuiltedFabricApiProvision(
                folder.resolve("game"),
                (uri, target) -> {
                    downloads.incrementAndGet();
                    Files.write(target, content);
                },
                status::add,
                version -> version.equals("1.20.1") ? Optional.of(release) : Optional.empty());
    }

    @Test
    void addsTheCachedJarOnceForQuiltModsThatNeedAQslModule() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        List<String> status = new ArrayList<>();
        QuiltedFabricApiProvision provision = provision(new byte[] {7}, downloads, status);
        List<DiscoveredModFile> files = List.of(quiltMod("a", "\"quilt_base\"", Map.of()));

        List<DiscoveredModFile> first = provision.provide(files, "1.20.1", FabricApiProvision.Host.NONE, false);
        List<DiscoveredModFile> second = provision.provide(files, "1.20.1", FabricApiProvision.Host.NONE, false);

        assertEquals(2, first.size());
        assertEquals(ModFileKind.QUILT, first.get(1).kind());
        assertEquals(
                folder.resolve("game/.plexus/quilted-fabric-api/7.7.0+0.92.2-1.20.1/qfapi-7.7.0_mc-1.20.1.jar"),
                first.get(1).path());
        assertEquals(first, second);
        assertEquals(1, downloads.get());
        assertEquals(1, status.size());
    }

    @Test
    void leavesTheModsAloneWhenNothingNeedsQslOrItIsThere() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        QuiltedFabricApiProvision provision = provision(new byte[] {7}, downloads, new ArrayList<>());
        byte[] nestedBase = jarBytes(Map.of(
                "quilt.mod.json",
                "{\"quilt_loader\":{\"id\":\"quilt_base\",\"version\":\"6\"}}".getBytes(StandardCharsets.UTF_8)));
        List<DiscoveredModFile> plain = List.of(quiltMod("plain", "\"quilt_loader\"", Map.of()));
        List<DiscoveredModFile> optional =
                List.of(quiltMod("opt", "{\"id\":\"quilt_base\",\"optional\":true}", Map.of()));
        List<DiscoveredModFile> waived = List.of(
                quiltMod("res", "{\"id\":\"quilt_resource_loader\",\"unless\":\"fabric-api\"}", Map.of()), fabricApi());
        List<DiscoveredModFile> nested =
                List.of(quiltMod("lib", "\"quilt_base\"", Map.of("META-INF/jars/base.jar", nestedBase)));

        assertSame(plain, provision.provide(plain, "1.20.1", FabricApiProvision.Host.NONE, false));
        assertSame(optional, provision.provide(optional, "1.20.1", FabricApiProvision.Host.NONE, false));
        assertSame(waived, provision.provide(waived, "1.20.1", FabricApiProvision.Host.NONE, false));
        assertSame(nested, provision.provide(nested, "1.20.1", FabricApiProvision.Host.NONE, false));
        assertEquals(0, downloads.get());
    }

    @Test
    void skipsFabricApiHostsVersionsWithoutQslAndTheSettingOff() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        QuiltedFabricApiProvision provision = provision(new byte[] {7}, downloads, new ArrayList<>());
        List<DiscoveredModFile> needs = List.of(quiltMod("a", "\"quilted_fabric_api\"", Map.of()));
        List<DiscoveredModFile> withFabricApi = List.of(needs.getFirst(), fabricApi());

        assertSame(needs, provision.provide(needs, "1.21.11", FabricApiProvision.Host.NONE, false));
        assertSame(needs, provision.provide(needs, "1.20.1", FabricApiProvision.Host.FORGE, false));
        assertSame(withFabricApi, provision.provide(withFabricApi, "1.20.1", FabricApiProvision.Host.NONE, false));
        System.setProperty("plexus.qsl", "off");
        assertSame(needs, provision.provide(needs, "1.20.1", FabricApiProvision.Host.NONE, false));
        // By default, as Fabric API: not in a development launch.
        System.clearProperty("plexus.qsl");
        assertSame(needs, provision.provide(needs, "1.20.1", FabricApiProvision.Host.NONE, true));
        assertEquals(0, downloads.get());
    }

    @Test
    void qslModuleIds() {
        assertTrue(QuiltedFabricApiProvision.isQslModule("qsl"));
        assertTrue(QuiltedFabricApiProvision.isQslModule("quilt_base"));
        assertTrue(QuiltedFabricApiProvision.isQslModule("quilted_fabric_api"));
        assertTrue(QuiltedFabricApiProvision.isQslModule("quilted_fabric_api_base"));
        assertFalse(QuiltedFabricApiProvision.isQslModule("quilt_loader"));
        assertFalse(QuiltedFabricApiProvision.isQslModule("fabric-api"));
    }

    @Test
    void theVersionsWithAQslReleaseArePinned() {
        for (String version : List.of("1.20.1", "1.20.4", "1.21.1")) {
            QuiltedFabricApiProvision.Release release =
                    QuiltedFabricApiProvision.release(version).orElseThrow();
            assertTrue(release.uri().toString().startsWith("https://cdn.modrinth.com/data/qvIfYCYJ/"), version);
            assertEquals(128, release.sha512().length(), version);
        }
        for (String version : List.of("1.21.11", "26.1", "26.2", "26.3")) {
            assertTrue(QuiltedFabricApiProvision.release(version).isEmpty(), version);
        }
    }
}
