package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.compat.bridge.SharedMixinConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Mixin configurations of Forge mods and of the jars nested in them, for a Mixin that serves every loader. */
class ForgeMixinConfigsTest {

    @Test
    void nestedJarsWithConfigurationsAreCopiedForTheSharedMixin(@TempDir Path directory) throws IOException {
        Path mod = directory.resolve("balm-forge.jar");
        write(
                mod,
                "balm.mixins.json",
                new byte[0],
                "META-INF/jarjar/kuma-api-forge-20.1.12+1.20.1.jar",
                nestedJar("kuma_api.mixins.json, kuma_api.forge.mixins.json"),
                "META-INF/jarjar/library-1.0.jar",
                nestedJar(null),
                "META-INF/jarjar/mixinextras-forge-0.5.0.jar",
                nestedJar("mixinextras.init.mixins.json"));

        List<Path> copies = ForgeMixinConfigs.extractNested(List.of(mod), directory.resolve("copies"));

        assertEquals(1, copies.size());
        assertTrue(
                copies.getFirst().getFileName().toString().startsWith("balm-forge.jar!kuma-api-forge-20.1.12+1.20.1"));
        try (JarFile copy = new JarFile(copies.getFirst().toFile())) {
            assertEquals(
                    "kuma_api.mixins.json, kuma_api.forge.mixins.json",
                    copy.getManifest().getMainAttributes().getValue("MixinConfigs"));
        }
        // The same configurations as the ones the shared Mixin is told to apply.
        List<SharedMixinConfig> configs = ForgeMixinConfigs.read(List.of(mod));
        assertEquals(
                List.of("kuma_api.mixins.json", "kuma_api.forge.mixins.json"),
                configs.stream().map(SharedMixinConfig::config).toList());
    }

    @Test
    void aCopyAlreadyThereIsKept(@TempDir Path directory) throws IOException {
        Path mod = directory.resolve("mod.jar");
        write(mod, "META-INF/jarjar/inner.jar", nestedJar("inner.mixins.json"));
        Path copies = directory.resolve("copies");

        Path first = ForgeMixinConfigs.extractNested(List.of(mod), copies).getFirst();
        long modified = Files.getLastModifiedTime(first).toMillis();
        Path second = ForgeMixinConfigs.extractNested(List.of(mod), copies).getFirst();

        assertEquals(first, second);
        assertEquals(modified, Files.getLastModifiedTime(second).toMillis());
    }

    @Test
    void aJarWithoutNestedJarsHasNoCopies(@TempDir Path directory) throws IOException {
        Path mod = directory.resolve("plain.jar");
        write(mod, "plain.txt", new byte[0]);

        assertTrue(ForgeMixinConfigs.extractNested(List.of(mod), directory.resolve("copies"))
                .isEmpty());
        assertTrue(!Files.exists(directory.resolve("copies")));
    }

    private static byte[] nestedJar(String mixinConfigs) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mixinConfigs != null) {
            manifest.getMainAttributes().putValue("MixinConfigs", mixinConfigs);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes, manifest)) {
            out.putNextEntry(new JarEntry("marker.txt"));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void write(Path file, Object... namesAndContents) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new JarEntry((String) namesAndContents[i]));
                out.write((byte[]) namesAndContents[i + 1]);
                out.closeEntry();
            }
        }
    }
}
