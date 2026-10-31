package dev.plexusmodloader.runtime.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.compat.bridge.CompatibilityFinding;
import dev.plexusmodloader.runtime.discovery.ForeignModResolver.ForeignMod;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plexus Mod Loader's host modules (ADR-0031) in place of mods left out for not fitting the host's patch. */
class HostModulesTest {

    private static final String HOST = "NeoForge 26.2.0.93";
    private static final String RESOURCE = "META-INF/plexusmodloader/host-modules/neoforge-26.2.0.93/block-host.jar";
    private static final String ALWAYS_RESOURCE =
            "META-INF/plexusmodloader/host-modules/neoforge-26.2.0.93/object-builder-host.jar";

    @TempDir
    Path directory;

    private URLClassLoader loader;
    private ForeignModResolver bridges;

    /** The test bridge as a service, and an index that lists a host module for {@code fabric-block-api-v1}. */
    @BeforeEach
    void install() throws IOException {
        Path classes = directory.resolve("classes");
        Path services = classes.resolve("META-INF/services/dev.plexusmodloader.compat.bridge.ModLoaderBridge");
        Files.createDirectories(services.getParent());
        Files.writeString(services, TestJarInJarBridge.class.getName());
        Path index = classes.resolve(HostModules.INDEX);
        Files.createDirectories(index.getParent());
        Files.writeString(
                index,
                "neoforge-26.2.0.93.fabric-block-api-v1=" + RESOURCE + "\n"
                        + "neoforge-26.2.0.93.fabric-object-builder-api-v1=" + ALWAYS_RESOURCE + "\n"
                        + "neoforge-26.2.0.93.fabric-object-builder-api-v1.always=true\n");
        Path hostModule = classes.resolve(RESOURCE);
        Files.createDirectories(hostModule.getParent());
        fabricMod(hostModule, "fabric-block-api-v1", "3.1.0+plexus.neoforge.26.2");
        fabricMod(classes.resolve(ALWAYS_RESOURCE), "fabric-object-builder-api-v1", "24.1.1+plexus.neoforge.26.2");
        loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
        bridges = new ForeignModResolver(loader);
    }

    @AfterEach
    void close() throws IOException {
        loader.close();
    }

    @Test
    void aModLeftOutForTheHostsPatchRunsAsTheHostModule() throws IOException {
        ForeignMod original = leftOut(mod("fabric-block-api-v1", "3.1.0+53515aab9e"));
        ForeignMod other = mod("fabric-api-base", "2.0.4");

        HostModules.Result result = HostModules.replaceLeftOut(
                HOST, List.of(original, other), bridges, GameSide.DEDICATED_SERVER, directory.resolve("out"), loader);

        assertEquals(1, result.replaced().size());
        ForeignMod replacement = result.mods().getFirst();
        assertEquals("fabric-block-api-v1", replacement.assessment().modId());
        assertEquals("3.1.0+plexus.neoforge.26.2", replacement.assessment().version());
        assertTrue(
                replacement.jar().startsWith(directory.resolve("out")),
                replacement.jar().toString());
        assertSame(other, result.mods().get(1));
    }

    @Test
    void aModThatFitsTheHostStaysAsItIs() throws IOException {
        ForeignMod fits = mod("fabric-block-api-v1", "3.1.0+53515aab9e");

        HostModules.Result result = HostModules.replaceLeftOut(
                HOST, List.of(fits), bridges, GameSide.DEDICATED_SERVER, directory.resolve("out"), loader);

        assertEquals(List.of(), result.replaced());
        assertSame(fits, result.mods().getFirst());
    }

    @Test
    void aHostModuleMarkedAlwaysReplacesAnOriginalThatFitsTheHost() throws IOException {
        ForeignMod fits = mod("fabric-object-builder-api-v1", "24.1.1+6fcd5f039e");

        HostModules.Result result = HostModules.replaceLeftOut(
                HOST, List.of(fits), bridges, GameSide.DEDICATED_SERVER, directory.resolve("out"), loader);

        assertEquals(1, result.replaced().size());
        assertEquals(HostModules.Reason.ALWAYS, result.replaced().getFirst().reason());
        assertEquals(fits.jar(), result.replaced().getFirst().original());
        assertEquals(
                "24.1.1+plexus.neoforge.26.2",
                result.mods().getFirst().assessment().version());
    }

    @Test
    void aReplacementOfALeftOutModSaysSo() throws IOException {
        ForeignMod original = leftOut(mod("fabric-block-api-v1", "3.1.0+53515aab9e"));

        HostModules.Result result = HostModules.replaceLeftOut(
                HOST, List.of(original), bridges, GameSide.DEDICATED_SERVER, directory.resolve("out"), loader);

        assertEquals(HostModules.Reason.LEFT_OUT, result.replaced().getFirst().reason());
        assertEquals(
                result.mods().getFirst().jar(), result.replaced().getFirst().hostModule());
    }

    /** Several mods nest the module (a Quilt mod set with three Fabric API copies): one host module stands for all. */
    @Test
    void copiesOfALeftOutModuleBecomeOneHostModule() throws IOException {
        ForeignMod older = leftOut(mod("fabric-block-api-v1", "3.0.3+ec56b6019e"));
        ForeignMod other = mod("fabric-api-base", "2.0.4");
        ForeignMod newer = leftOut(mod("fabric-block-api-v1", "3.1.0+53515aab9e"));

        HostModules.Result result = HostModules.replaceLeftOut(
                HOST,
                List.of(older, other, newer),
                bridges,
                GameSide.DEDICATED_SERVER,
                directory.resolve("out"),
                loader);

        assertEquals(2, result.mods().size());
        assertEquals(
                "3.1.0+plexus.neoforge.26.2",
                result.mods().getFirst().assessment().version());
        assertSame(other, result.mods().get(1));
        assertEquals(
                List.of(older.jar(), newer.jar()),
                result.replaced().stream()
                        .map(HostModules.Replacement::original)
                        .toList());
    }

    /** A copy that fits the patch would otherwise compete with the host module for the same ID. */
    @Test
    void aCopyThatFitsNextToALeftOutCopyGoesWithIt() throws IOException {
        ForeignMod fits = mod("fabric-block-api-v1", "3.0.3+ec56b6019e");
        ForeignMod blocked = leftOut(mod("fabric-block-api-v1", "3.1.0+53515aab9e"));

        HostModules.Result result = HostModules.replaceLeftOut(
                HOST, List.of(fits, blocked), bridges, GameSide.DEDICATED_SERVER, directory.resolve("out"), loader);

        assertEquals(1, result.mods().size());
        assertEquals(
                "3.1.0+plexus.neoforge.26.2",
                result.mods().getFirst().assessment().version());
        assertEquals(
                List.of(HostModules.Reason.ANOTHER_COPY, HostModules.Reason.LEFT_OUT),
                result.replaced().stream().map(HostModules.Replacement::reason).toList());
    }

    @Test
    void anotherHostReleaseHasNoHostModule() throws IOException {
        ForeignMod original = leftOut(mod("fabric-block-api-v1", "3.1.0+53515aab9e"));

        HostModules.Result result = HostModules.replaceLeftOut(
                "NeoForge 26.2.0.99",
                List.of(original),
                bridges,
                GameSide.DEDICATED_SERVER,
                directory.resolve("out"),
                loader);

        assertEquals(List.of(), result.replaced());
        assertSame(original, result.mods().getFirst());
    }

    @Test
    void theHostKeyIsTheLowerCaseNameWithDashes() {
        assertEquals("neoforge-26.2.0.93", HostModules.hostKey(" NeoForge 26.2.0.93 "));
    }

    private ForeignMod mod(String id, String version) throws IOException {
        Path jar = directory.resolve(id + "-" + version + ".jar");
        fabricMod(jar, id, version);
        return bridges.assess(ModFileScanner.classify(jar), GameSide.DEDICATED_SERVER)
                .orElseThrow();
    }

    private static ForeignMod leftOut(ForeignMod mod) {
        return mod.withFindings(List.of(new CompatibilityFinding("PLEXUS-COMPAT-224", true, "mixins", "test")));
    }

    private static void fabricMod(Path jar, String id, String version) throws IOException {
        try (OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write(("{\"schemaVersion\": 1, \"id\": \"" + id + "\", \"version\": \"" + version + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
