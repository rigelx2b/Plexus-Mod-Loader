package dev.plexusmodloader.compat.fabric.intermediary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.compat.bridge.CompatibilityFinding;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntermediaryTranslationsTest {

    private static final List<String> PROPERTIES = List.of(
            "plexus.gameJar",
            "plexus.reportDir",
            "plexus.mojangMappings",
            "plexus.intermediaryMappings",
            "plexus.intermediaryUrl",
            "plexus.intermediaryIdleMillis");

    @AfterEach
    void clean() {
        PROPERTIES.forEach(System::clearProperty);
        IntermediaryTranslations.reset();
    }

    /** A game folder like the installer's: the remapped jar and, next to it, both mappings. */
    private static Path gameFolder(Path temp, String version, boolean withMojangMappings) throws IOException {
        Path folder = Files.createDirectories(temp.resolve("game"));
        Path jar = folder.resolve("client-" + version + "-mojang.jar");
        try (OutputStream file = Files.newOutputStream(jar);
                ZipOutputStream out = new ZipOutputStream(file)) {
            out.putNextEntry(new ZipEntry("version.json"));
            out.write(("{\"id\":\"" + version + "\",\"name\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        if (withMojangMappings) {
            Files.writeString(folder.resolve("client-mappings-" + version + ".txt"), TestMappings.PROGUARD);
        }
        try (OutputStream file = Files.newOutputStream(folder.resolve("intermediary-" + version + "-v2.jar"));
                ZipOutputStream out = new ZipOutputStream(file)) {
            out.putNextEntry(new ZipEntry("mappings/mappings.tiny"));
            out.write(TestMappings.TINY.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static Path modJar(Path temp, String name) throws IOException {
        Path jar = temp.resolve(name);
        ModJarTranslatorTest.writeJar(jar, ModJarTranslatorTest.fullMod());
        return jar;
    }

    @Test
    void anObfuscatedGameGetsATranslatedCopyKeptBetweenRuns(@TempDir Path temp) throws IOException {
        Path gameJar = gameFolder(temp, "1.21.1", true);
        System.setProperty("plexus.gameJar", gameJar.toString());
        System.setProperty("plexus.reportDir", temp.resolve(".plexus").toString());
        Path mod = modJar(temp, "mod.jar");

        IntermediaryTranslations.Prepared first = IntermediaryTranslations.prepare(mod);

        assertNotEquals(mod, first.jar());
        assertTrue(first.jar()
                .startsWith(temp.resolve(".plexus").resolve("translated-mods").resolve("1.21.1")));
        assertEquals(List.of("PLEXUS-COMPAT-142"), codes(first.findings()));
        assertFalse(first.findings().getFirst().blocking());
        assertTrue(ModJarTranslatorTest.text(first.jar(), "m.accesswidener").startsWith("accessWidener\tv2\tofficial"));

        byte[] bytes = Files.readAllBytes(first.jar());
        IntermediaryTranslations.reset();
        IntermediaryTranslations.Prepared second = IntermediaryTranslations.prepare(mod);
        assertEquals(first.jar(), second.jar());
        assertTrue(
                java.util.Arrays.equals(bytes, Files.readAllBytes(second.jar())), "the copy is reused, not made again");
        assertEquals(List.of("PLEXUS-COMPAT-142"), codes(second.findings()));
    }

    @Test
    void aLaunchWhoseModsAreTranslatedAlreadyNeverReadsTheMappings(@TempDir Path temp) throws IOException {
        Path gameJar = gameFolder(temp, "1.21.1", true);
        System.setProperty("plexus.gameJar", gameJar.toString());
        System.setProperty("plexus.reportDir", temp.resolve(".plexus").toString());
        Path mod = modJar(temp, "mod.jar");
        IntermediaryTranslations.prepare(mod);
        assertTrue(IntermediaryTranslations.tablesLoaded(), "the first launch had to translate");

        IntermediaryTranslations.reset();
        int before = IntermediaryTranslations.tablesLoads();
        IntermediaryTranslations.Prepared second = IntermediaryTranslations.prepare(mod);

        assertEquals(List.of("PLEXUS-COMPAT-142"), codes(second.findings()));
        assertEquals(before, IntermediaryTranslations.tablesLoads(), "the copy was found by name");
        assertFalse(IntermediaryTranslations.tablesLoaded());
        assertTrue(IntermediaryTranslations.available(), "answering that intermediary exists does not read it either");
        assertEquals(before, IntermediaryTranslations.tablesLoads());
    }

    @Test
    void releasedTablesAreReadAgainWhenAModAsksTheResolver(@TempDir Path temp) throws IOException {
        Path gameJar = gameFolder(temp, "1.21.1", true);
        System.setProperty("plexus.gameJar", gameJar.toString());
        System.setProperty("plexus.reportDir", temp.resolve(".plexus").toString());
        IntermediaryTranslations.prepare(modJar(temp, "mod.jar"));
        assertTrue(IntermediaryTranslations.tablesLoaded());

        IntermediaryTranslations.releaseTables();

        assertFalse(IntermediaryTranslations.tablesLoaded(), "the tables are let go once the mods are prepared");
        int before = IntermediaryTranslations.tablesLoads();
        assertTrue(IntermediaryTranslations.translation().isPresent());
        assertEquals(before + 1, IntermediaryTranslations.tablesLoads());
        assertTrue(IntermediaryTranslations.tablesLoaded());
    }

    @Test
    void tablesNobodyUsesForAWhileAreLetGo(@TempDir Path temp) throws IOException, InterruptedException {
        Path gameJar = gameFolder(temp, "1.21.1", true);
        System.setProperty("plexus.gameJar", gameJar.toString());
        System.setProperty("plexus.reportDir", temp.resolve(".plexus").toString());
        System.setProperty("plexus.intermediaryIdleMillis", "200");

        IntermediaryTranslations.prepare(modJar(temp, "mod.jar"));
        assertTrue(IntermediaryTranslations.tablesLoaded());

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (IntermediaryTranslations.tablesLoaded() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(IntermediaryTranslations.tablesLoaded(), "idle tables stay in memory");
    }

    @Test
    void copiesNobodyUsedForTwoMonthsGoWhenANewOneIsMadeAndUsedOnesStay(@TempDir Path temp) throws IOException {
        Path gameJar = gameFolder(temp, "1.21.1", true);
        System.setProperty("plexus.gameJar", gameJar.toString());
        System.setProperty("plexus.reportDir", temp.resolve(".plexus").toString());
        Path cache = temp.resolve(".plexus").resolve("translated-mods").resolve("1.21.1");
        Files.createDirectories(cache);
        Path stale = Files.write(cache.resolve("old-1f-2a-0123abcd.jar"), new byte[] {1});
        Files.setLastModifiedTime(
                stale,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(90))));
        Path recent = Files.write(cache.resolve("recent-1f-2a-0123abcd.jar"), new byte[] {2});
        Path foreign = Files.write(cache.resolve("notes.txt"), new byte[] {3});
        Files.setLastModifiedTime(
                foreign,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(90))));

        IntermediaryTranslations.prepare(modJar(temp, "mod.jar"));

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(foreign), "only files of the shape this class writes are ever deleted");
    }

    @Test
    void aModWithoutIntermediaryNamesKeepsItsJarAndIsNotTranslatedAgain(@TempDir Path temp) throws IOException {
        Path gameJar = gameFolder(temp, "1.21.1", true);
        System.setProperty("plexus.gameJar", gameJar.toString());
        System.setProperty("plexus.reportDir", temp.resolve(".plexus").toString());
        Path mod = temp.resolve("lib.jar");
        ModJarTranslatorTest.writeJar(
                mod,
                Map.of(
                        "fabric.mod.json",
                        "{\"id\":\"lib\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8),
                        "x/Plain.class",
                        ModJarTranslatorTest.plainClass()));

        assertEquals(mod, IntermediaryTranslations.prepare(mod).jar());
        assertEquals(mod, IntermediaryTranslations.prepare(mod).jar());
        assertTrue(Files.list(temp.resolve(".plexus").resolve("translated-mods").resolve("1.21.1"))
                .anyMatch(file -> file.toString().endsWith(".same")));
    }

    @Test
    void anUnobfuscatedGameChangesNothing(@TempDir Path temp) throws IOException {
        Path gameJar = gameFolder(temp, "26.2", true);
        System.setProperty("plexus.gameJar", gameJar.toString());
        Path mod = modJar(temp, "mod.jar");

        IntermediaryTranslations.Prepared prepared = IntermediaryTranslations.prepare(mod);

        assertEquals(mod, prepared.jar());
        assertEquals(List.of(), prepared.findings());
    }

    @Test
    void withoutAGameJarNothingChanges(@TempDir Path temp) throws IOException {
        System.setProperty("plexus.gameJar", temp.resolve("missing.jar").toString());
        Path mod = modJar(temp, "mod.jar");

        assertEquals(mod, IntermediaryTranslations.prepare(mod).jar());
    }

    @Test
    void missingMojangMappingsBlockOnlyModsThatNeedThem(@TempDir Path temp) throws IOException {
        Path gameJar = gameFolder(temp, "1.21.1", false);
        System.setProperty("plexus.gameJar", gameJar.toString());
        Path mod = modJar(temp, "mod.jar");
        Path lib = temp.resolve("lib.jar");
        ModJarTranslatorTest.writeJar(lib, Map.of("x/Plain.class", ModJarTranslatorTest.plainClass()));

        IntermediaryTranslations.Prepared needing = IntermediaryTranslations.prepare(mod);
        IntermediaryTranslations.Prepared plain = IntermediaryTranslations.prepare(lib);

        assertEquals(mod, needing.jar());
        assertEquals(List.of("PLEXUS-COMPAT-143"), codes(needing.findings()));
        assertTrue(needing.findings().getFirst().blocking());
        assertTrue(needing.findings().getFirst().detail().contains("client-mappings-1.21.1.txt"));
        assertEquals(List.of(), plain.findings());
    }

    @Test
    void aMissingIntermediaryFileThatCannotBeDownloadedBlocksWithTheReason(@TempDir Path temp) throws IOException {
        Path gameJar = gameFolder(temp, "1.21.1", true);
        Files.delete(gameJar.resolveSibling("intermediary-1.21.1-v2.jar"));
        System.setProperty("plexus.gameJar", gameJar.toString());
        // Nothing listens here, so the download fails at once.
        System.setProperty("plexus.intermediaryUrl", "http://127.0.0.1:9");
        Path mod = modJar(temp, "mod.jar");

        IntermediaryTranslations.Prepared prepared = IntermediaryTranslations.prepare(mod);

        assertEquals(mod, prepared.jar());
        assertEquals(List.of("PLEXUS-COMPAT-143"), codes(prepared.findings()));
        assertTrue(prepared.findings().getFirst().detail().contains("intermediary mappings"));
    }

    @Test
    void obfuscatedVersionsAreTheOnesBefore26() {
        assertTrue(IntermediaryTranslations.isObfuscated("1.20.1"));
        assertTrue(IntermediaryTranslations.isObfuscated("1.21.11"));
        assertTrue(IntermediaryTranslations.isObfuscated("24w14a"));
        assertFalse(IntermediaryTranslations.isObfuscated("26.1"));
        assertFalse(IntermediaryTranslations.isObfuscated("26.3"));
        assertFalse(IntermediaryTranslations.isObfuscated("unknown"));
    }

    private static List<String> codes(List<CompatibilityFinding> findings) {
        return findings.stream().map(CompatibilityFinding::code).toList();
    }
}
