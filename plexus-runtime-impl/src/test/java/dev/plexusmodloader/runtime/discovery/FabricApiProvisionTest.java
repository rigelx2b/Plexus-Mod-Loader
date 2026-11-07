package dev.plexusmodloader.runtime.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FabricApiProvisionTest {

    @TempDir
    Path folder;

    private Path jar(String name, String entry, String json) throws IOException {
        Path file = folder.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
                JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(json.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return file;
    }

    private DiscoveredModFile fabricMod(String id) throws IOException {
        return new DiscoveredModFile(
                jar(id + ".jar", "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\"}"),
                ModFileKind.FABRIC,
                Optional.empty());
    }

    private static String sha512(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
    }

    /** A provision whose release is {@code content}, counting downloads, with the status lines in {@code status}. */
    private FabricApiProvision provision(byte[] content, AtomicInteger downloads, List<String> status)
            throws Exception {
        FabricApiProvision.Release release = new FabricApiProvision.Release(
                "26.3",
                "0.165.0",
                URI.create("https://cdn.modrinth.com/data/P7dR8mSH/versions/x/fabric-api-0.165.0%2B26.3.jar"),
                sha512(content));
        return new FabricApiProvision(
                folder.resolve("game"),
                (uri, target) -> {
                    downloads.incrementAndGet();
                    Files.write(target, content);
                },
                status::add,
                version -> version.equals("26.3") ? Optional.of(release) : Optional.empty());
    }

    @Test
    void addsTheCachedJarOnceForFabricModsWithoutFabricApi() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        List<String> status = new ArrayList<>();
        FabricApiProvision provision = provision("jar".getBytes(StandardCharsets.UTF_8), downloads, status);
        List<DiscoveredModFile> files = List.of(fabricMod("sodium"));

        List<DiscoveredModFile> first = provision.provide(files, "26.3", FabricApiProvision.Host.NONE, false);
        List<DiscoveredModFile> second = provision.provide(files, "26.3", FabricApiProvision.Host.NONE, false);

        assertEquals(2, first.size());
        DiscoveredModFile added = first.get(1);
        assertEquals(ModFileKind.FABRIC, added.kind());
        assertEquals(folder.resolve("game/.plexus/fabric-api/0.165.0/fabric-api-0.165.0+26.3.jar"), added.path());
        assertEquals(first, second);
        assertEquals(1, downloads.get(), "a later launch only checks the cached jar's hash");
        assertEquals(1, status.size(), "the player sees the download");
    }

    @Test
    void leavesTheModsAloneWhenFabricApiIsThereOrNotNeeded() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        FabricApiProvision provision = provision(new byte[] {1}, downloads, new ArrayList<>());
        List<DiscoveredModFile> withOwn = List.of(fabricMod("sodium"), fabricMod(FabricApiProvision.FABRIC_API));
        DiscoveredModFile quilted = new DiscoveredModFile(
                jar(
                        "qfapi.jar",
                        "quilt.mod.json",
                        "{\"quilt_loader\":{\"id\":\"quilted_fabric_api\",\"provides\":[{\"id\":\"fabric-api\"}]}}"),
                ModFileKind.QUILT,
                Optional.empty());
        List<DiscoveredModFile> withQuilted = List.of(fabricMod("sodium"), quilted);
        List<DiscoveredModFile> noFabric =
                List.of(new DiscoveredModFile(folder.resolve("forge.jar"), ModFileKind.FORGE, Optional.empty()));

        assertSame(withOwn, provision.provide(withOwn, "26.3", FabricApiProvision.Host.NONE, false));
        assertSame(withQuilted, provision.provide(withQuilted, "26.3", FabricApiProvision.Host.NONE, false));
        assertSame(noFabric, provision.provide(noFabric, "26.3", FabricApiProvision.Host.FORGE, false));
        // CustomSkinLoader's jar for every loader: fabric.mod.json and a ModLauncher service that Forge runs.
        List<DiscoveredModFile> runByForge = List.of(new DiscoveredModFile(
                folder.resolve("customskinloader.jar"),
                ModFileKind.FABRIC,
                Optional.empty(),
                Optional.empty(),
                java.util.Set.of(ModFileKind.FORGE)));
        assertSame(runByForge, provision.provide(runByForge, "1.21.11", FabricApiProvision.Host.FORGE, false));
        assertEquals(0, downloads.get());
    }

    @Test
    void skipsHostsAndVersionsWhereFabricApiDoesNotRunAndDevelopmentLaunches() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        FabricApiProvision provision = provision(new byte[] {1}, downloads, new ArrayList<>());
        List<DiscoveredModFile> files = List.of(fabricMod("sodium"));

        assertSame(files, provision.provide(files, "26.3", FabricApiProvision.Host.NONE, true));
        assertSame(files, provision.provide(files, "1.20.1", FabricApiProvision.Host.NONE, false));
        // Forge 47 and 49 make Ingredient.toNetwork final; Fabric API's override of it runs there (ADR-0047).
        assertTrue(FabricApiProvision.runsNextTo(FabricApiProvision.Host.FORGE, "1.20.1"));
        assertTrue(FabricApiProvision.runsNextTo(FabricApiProvision.Host.FORGE, "1.20.4"));
        assertTrue(FabricApiProvision.runsNextTo(FabricApiProvision.Host.FORGE, "1.21.1"));
        assertFalse(FabricApiProvision.runsNextTo(FabricApiProvision.Host.NEOFORGE, "1.20.4"));
        assertFalse(FabricApiProvision.runsNextTo(FabricApiProvision.Host.NEOFORGE, "1.21.1"));
        assertTrue(FabricApiProvision.runsNextTo(FabricApiProvision.Host.FORGE, "26.3"));
        assertTrue(FabricApiProvision.runsNextTo(FabricApiProvision.Host.NEOFORGE, "1.21.11"));
        assertEquals(0, downloads.get());
    }

    @Test
    void settingTurnsItOnOrOff() {
        assertTrue(FabricApiProvision.enabled("auto", false));
        assertFalse(FabricApiProvision.enabled("auto", true));
        assertTrue(FabricApiProvision.enabled("on", true));
        assertFalse(FabricApiProvision.enabled("off", false));
    }

    @Test
    void aDamagedDownloadIsNotUsed() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        FabricApiProvision.Release release = new FabricApiProvision.Release(
                "26.3", "0.165.0", URI.create("https://example.invalid/fabric-api-0.165.0%2B26.3.jar"), "00");
        FabricApiProvision provision = new FabricApiProvision(
                folder.resolve("game"),
                (uri, target) -> {
                    downloads.incrementAndGet();
                    Files.write(target, new byte[] {1});
                },
                line -> {},
                version -> Optional.of(release));
        List<DiscoveredModFile> files = List.of(fabricMod("sodium"));

        assertSame(files, provision.provide(files, "26.3", FabricApiProvision.Host.NONE, false));
        assertFalse(Files.exists(folder.resolve("game/.plexus/fabric-api/0.165.0/fabric-api-0.165.0+26.3.jar")));
        assertEquals(1, downloads.get());
    }

    @Test
    void everySupportedVersionHasAPinnedRelease() {
        for (String version :
                List.of("1.20.1", "1.20.4", "1.21.1", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3")) {
            FabricApiProvision.Release release =
                    FabricApiProvision.release(version).orElseThrow();
            assertTrue(release.uri().toString().startsWith("https://cdn.modrinth.com/data/P7dR8mSH/"), version);
            assertEquals(128, release.sha512().length(), version);
            assertTrue(release.fileName().startsWith("fabric-api-" + release.version() + "+"), version);
        }
    }
}
