package dev.plexusmodloader.runtime.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModFileScannerTest {

    @TempDir
    Path mods;

    private Path jar(String name, Manifest manifest, String entry, String text) throws IOException {
        Path path = mods.resolve(name);
        try (OutputStream file = Files.newOutputStream(path);
                JarOutputStream jar =
                        manifest == null ? new JarOutputStream(file) : new JarOutputStream(file, manifest)) {
            if (entry != null) {
                jar.putNextEntry(new JarEntry(entry));
                jar.write(text.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return path;
    }

    private Path jar(String name, String... entries) throws IOException {
        Path path = mods.resolve(name);
        try (OutputStream file = Files.newOutputStream(path);
                JarOutputStream jar = new JarOutputStream(file)) {
            for (String entry : entries) {
                jar.putNextEntry(new JarEntry(entry));
                jar.closeEntry();
            }
        }
        return path;
    }

    private List<DiscoveredModFile> scan() {
        return new ModFileScanner().scan(mods, List.of());
    }

    @Test
    void classifiesByMetadataFile() throws IOException {
        jar("a.jar", null, "plexus.mod.json", "{}");
        jar("b.jar", null, "fabric.mod.json", "{}");
        jar("c.jar", null, "META-INF/neoforge.mods.toml", "");
        jar("d.jar", null, "META-INF/mods.toml", "");
        jar("e.jar", null, "readme.txt", "hi");

        assertEquals(
                List.of(
                        ModFileKind.PLEXUS_NATIVE,
                        ModFileKind.FABRIC,
                        ModFileKind.NEOFORGE,
                        ModFileKind.FORGE,
                        ModFileKind.UNKNOWN),
                scan().stream().map(DiscoveredModFile::kind).toList());
    }

    /** The former file name is no longer read: such a jar has no known metadata (PLEXUS-DISCOVERY-002). */
    @Test
    void aJarWithOnlyTheFormerPolyModJsonIsNotAMod() throws IOException {
        jar("former.jar", null, "poly.mod.json", "{\"id\": \"former\"}");

        List<DiscoveredModFile> files = scan();
        assertEquals(1, files.size());
        assertEquals(ModFileKind.UNKNOWN, files.getFirst().kind());
        assertTrue(files.getFirst().nativeMetadataJson().isEmpty());
    }

    @Test
    void classifiesPlexusModJson() throws IOException {
        jar("plexus.jar", null, "plexus.mod.json", "{\"id\": \"test\"}");

        List<DiscoveredModFile> files = scan();
        assertEquals(1, files.size());
        assertEquals(ModFileKind.PLEXUS_NATIVE, files.getFirst().kind());
        assertTrue(files.getFirst().nativeMetadataJson().orElseThrow().contains("test"));
    }

    @Test
    void aJarWithAnFmlModTypeIsAnFmlLibrary() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("FMLModType", "LANGPROVIDER");
        jar("kotlinforforge.jar", manifest, "kotlin/Unit.class", "");

        DiscoveredModFile file = scan().getFirst();
        assertEquals(ModFileKind.FML_LIBRARY, file.kind());
        assertEquals("LANGPROVIDER", file.detail().orElseThrow());
    }

    /** One jar for every loader: its kind is the first in ModFileKind's order, and it remembers the others. */
    @Test
    void aJarForSeveralLoadersRemembersEachLoader() throws IOException {
        jar("all.jar", "fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml");
        jar("forge-and-neoforge.jar", "META-INF/neoforge.mods.toml", "META-INF/mods.toml");
        jar("fabric.jar", "fabric.mod.json");

        List<DiscoveredModFile> files = scan();
        assertEquals(ModFileKind.FABRIC, files.get(0).kind());
        assertEquals(
                Set.of(ModFileKind.NEOFORGE, ModFileKind.FORGE), files.get(0).alsoFor());
        assertEquals(ModFileKind.FABRIC, files.get(1).kind());
        assertEquals(Set.of(), files.get(1).alsoFor());
        assertEquals(ModFileKind.NEOFORGE, files.get(2).kind());
        assertTrue(files.get(2).isFor(ModFileKind.FORGE));
    }

    private static String modsToml(String own, String... dependencies) {
        StringBuilder toml = new StringBuilder("modLoader=\"javafml\"\nloaderVersion=\"[2,)\"\nlicense=\"MIT\"\n");
        toml.append("[[mods]]\nmodId=\"").append(own).append("\"\nversion=\"1\"\n");
        for (String dependency : dependencies) {
            toml.append("[[dependencies.")
                    .append(own)
                    .append("]]\n    modId = \"")
                    .append(dependency)
                    .append("\" # needed\n    type=\"required\"\n");
        }
        return toml.toString();
    }

    private Path jarWith(String name, java.util.Map<String, String> entries) throws IOException {
        Path path = mods.resolve(name);
        try (OutputStream file = Files.newOutputStream(path);
                JarOutputStream jar = new JarOutputStream(file)) {
            for (java.util.Map.Entry<String, String> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return path;
    }

    private DiscoveredModFile scanned(String name) {
        return scan().stream()
                .filter(file -> file.path().getFileName().toString().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /** NeoForge 20.4's mods have META-INF/mods.toml only; the mods they depend on say which loader they are for. */
    @Test
    void aModsTomlThatDependsOnNeoForgeAndNotOnForgeIsANeoForgeMod() throws IOException {
        jar("neo.jar", null, "META-INF/mods.toml", modsToml("neo", "neoforge", "minecraft"));
        jar("forge.jar", null, "META-INF/mods.toml", modsToml("forge_mod", "forge", "minecraft"));
        jar("plain.jar", null, "META-INF/mods.toml", modsToml("plain", "minecraft"));

        assertEquals(ModFileKind.NEOFORGE, scanned("neo.jar").kind());
        assertEquals(Set.of(), scanned("neo.jar").alsoFor());
        assertEquals(ModFileKind.FORGE, scanned("forge.jar").kind());
        assertEquals(ModFileKind.FORGE, scanned("plain.jar").kind());
    }

    /** tr7zw's 1.20.4 NeoForge jars (Entity Culling): a mods.toml for NeoForge 20.4 beside a neoforge.mods.toml. */
    @Test
    void aJarWithBothFilesWhoseModsTomlDependsOnNeoForgeIsNotForForge() throws IOException {
        jarWith(
                "entityculling.jar",
                java.util.Map.of(
                        "META-INF/neoforge.mods.toml", modsToml("entityculling", "neoforge", "minecraft"),
                        "META-INF/mods.toml", modsToml("entityculling", "neoforge", "minecraft")));
        jarWith(
                "forge-and-neoforge.jar",
                java.util.Map.of(
                        "META-INF/neoforge.mods.toml", modsToml("multi", "neoforge", "minecraft"),
                        "META-INF/mods.toml", modsToml("multi", "forge", "minecraft")));

        assertEquals(ModFileKind.NEOFORGE, scanned("entityculling.jar").kind());
        assertEquals(Set.of(), scanned("entityculling.jar").alsoFor());
        assertEquals(ModFileKind.NEOFORGE, scanned("forge-and-neoforge.jar").kind());
        assertEquals(
                Set.of(ModFileKind.FORGE), scanned("forge-and-neoforge.jar").alsoFor());
    }

    /** A jar for several loaders keeps its first kind; its mods.toml then names NeoForge, not Forge, as the other one. */
    @Test
    void aFabricJarWhoseModsTomlIsNeoForgesIsAlsoForNeoForgeNotForge() throws IOException {
        jarWith(
                "multi.jar",
                java.util.Map.of(
                        "fabric.mod.json", "{}", "META-INF/mods.toml", modsToml("multi", "neoforge", "minecraft")));
        jarWith(
                "multi-forge.jar",
                java.util.Map.of(
                        "fabric.mod.json", "{}", "META-INF/mods.toml", modsToml("multi_forge", "forge", "minecraft")));

        DiscoveredModFile neo = scanned("multi.jar");
        assertEquals(ModFileKind.FABRIC, neo.kind());
        assertEquals(Set.of(ModFileKind.NEOFORGE), neo.alsoFor());
        DiscoveredModFile forge = scanned("multi-forge.jar");
        assertEquals(Set.of(ModFileKind.FORGE), forge.alsoFor());
    }
    /** A jar built for both Forge and NeoForge runs on whichever of the two the launch runs (ADR-0043). */
    @Test
    void aModsTomlThatDependsOnBothIsForEitherAndOnlyTheOtherLoadersMetadataCounts() throws IOException {
        jar("both.jar", null, "META-INF/mods.toml", modsToml("both", "forge", "neoforge"));
        jar("two-files.jar", null, "META-INF/neoforge.mods.toml", "");

        List<DiscoveredModFile> files = scan();
        DiscoveredModFile both = files.get(0);
        assertEquals(ModFileKind.FORGE, both.kind());
        assertEquals(Set.of(ModFileKind.NEOFORGE), both.alsoFor());
        assertTrue(both.isFor(ModFileKind.NEOFORGE));
    }

    @Test
    void theDependencyTablesDecideNotTheModsOwnId() {
        // A mod called "neoforge" in [[mods]] is not a dependency on it; a quoted, commented or spaced modId is read.
        assertEquals(
                ModFileScanner.ModsTomlFamily.FORGE_ONLY,
                ModFileScanner.modsTomlFamily(
                        "[[mods]]\nmodId=\"neoforge\"\n[[dependencies.x]]\nmodId=\"minecraft\"\n"));
        assertEquals(
                ModFileScanner.ModsTomlFamily.NEOFORGE_ONLY,
                ModFileScanner.modsTomlFamily("[[dependencies.x]]\nmodId = 'neoforge'\r\ntype=\"required\"\n"));
        assertEquals(
                ModFileScanner.ModsTomlFamily.BOTH,
                ModFileScanner.modsTomlFamily(
                        "[[dependencies.x]]\n modId=\"forge\" # old\n[[dependencies.x]]\nmodId=\"neoforge\"\n"));
        assertEquals(ModFileScanner.ModsTomlFamily.FORGE_ONLY, ModFileScanner.modsTomlFamily("not toml at all ["));
    }

    /** CustomSkinLoader's jar: fabric.mod.json and a ModLauncher transformation service, which Forge runs. */
    @Test
    void aModLauncherTransformationServiceIsForForgeToo() throws IOException {
        jar("skins.jar", "fabric.mod.json", "META-INF/services/cpw.mods.modlauncher.api.ITransformationService");

        DiscoveredModFile file = scan().getFirst();
        assertEquals(ModFileKind.FABRIC, file.kind());
        assertTrue(file.isFor(ModFileKind.FORGE));
    }

    /** A damaged download used to throw out of discovery and stop the launch without naming other mods. */
    @Test
    void aFileThatIsNotAJarIsUnreadableNotFatal() throws IOException {
        Files.writeString(mods.resolve("cut-off.jar"), "this download was cut off");
        jar("good.jar", null, "fabric.mod.json", "{}");

        List<DiscoveredModFile> files = scan();
        assertEquals(
                List.of(ModFileKind.UNREADABLE, ModFileKind.FABRIC),
                files.stream().map(DiscoveredModFile::kind).toList());
        assertTrue(
                files.getFirst().detail().orElseThrow().contains("ZipException"),
                files.getFirst().detail().toString());
    }

    @Test
    void aQuiltModIsQuiltAndAJarWithBothFabricAndQuiltMetadataIsAFabricModAlsoForQuilt() throws IOException {
        jar("quilt.jar", "quilt.mod.json");
        jar("dual.jar", "quilt.mod.json", "fabric.mod.json");
        jar("json5.jar", "quilt.mod.json5");

        List<DiscoveredModFile> files = scan();

        assertEquals(ModFileKind.FABRIC, files.get(0).kind());
        assertEquals(Set.of(ModFileKind.QUILT), files.get(0).alsoFor());
        assertTrue(files.get(0).isFor(ModFileKind.QUILT));
        assertEquals(ModFileKind.UNKNOWN, files.get(1).kind());
        assertEquals(ModFileKind.QUILT, files.get(2).kind());
        assertEquals(Set.of(), files.get(2).alsoFor());
    }
}
