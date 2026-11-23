package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Forge on NeoForge (ADR-0051): a Forge mod's pack metadata that the NeoForge releases without a fallback would stop the
 * game on, against the NeoForge universal jars Plexus Mod Loader pins for Minecraft 26.x.
 */
class ForgePackMetadataCheckTest {

    @TempDir
    Path directory;

    /** NeoForge 26.1.0.19-beta and 26.1.1.15-beta read mod pack metadata without a fallback; later releases catch it. */
    @ParameterizedTest
    @CsvSource({
        "neoforge-26.1.0., true",
        "neoforge-26.1.1., true",
        "neoforge-26.1.2., false",
        "neoforge-26.2., false",
        "neoforge-26.3., false"
    })
    void theReleasesWithoutAFallbackAreFound(String neoForge, boolean stops) throws IOException {
        assertEquals(stops, ForgePackMetadataCheck.stopsOnRejectedMetadata(jar(neoForge)));
    }

    /** UniLib 1.2.1 and CraftPresence 2.7.1 for 26.1 declare a range from format 65 without supported_formats. */
    @Test
    void aRangeOfListedFormatsWithoutSupportedFormatsIsNamed() throws IOException {
        Path jar = modJar("{\"pack\": {\"description\": \"x\", \"min_format\": [65, 0], \"max_format\": [84, 0]}}");
        assertEquals(
                List.of("pack.mcmeta declares formats 65 to 84 without supported_formats, which Minecraft rejects for"
                        + " formats 17 to 81, and this NeoForge release stops the game on it"),
                ForgePackMetadataCheck.reasons(jar));
    }

    /**
     * What Minecraft takes: supported_formats with the range, a range above format 81 (Dungeons and Taverns 5.2.0: 101.1),
     * the old pack_format alone, and no pack.mcmeta.
     */
    @Test
    void metadataMinecraftTakesIsNotNamed() throws IOException {
        assertEquals(
                List.of(),
                ForgePackMetadataCheck.reasons(modJar("{\"pack\": {\"min_format\": [65, 0], \"max_format\": 84,"
                        + " \"supported_formats\": [65, 84]}}")));
        assertEquals(
                List.of(),
                ForgePackMetadataCheck.reasons(
                        modJar("{\"pack\": {\"min_format\": [101, 1], \"max_format\": [101, 1]}}")));
        assertEquals(List.of(), ForgePackMetadataCheck.reasons(modJar("{\"pack\": {\"pack_format\": 8}}")));
        assertEquals(List.of(), ForgePackMetadataCheck.reasons(modJar(null)));
    }

    private Path modJar(String packMetadata) throws IOException {
        Path jar = Files.createTempFile(directory, "mod", ".jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream output = new JarOutputStream(out)) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write("modLoader = \"javafml\"\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            if (packMetadata != null) {
                output.putNextEntry(new JarEntry(ForgePackMetadataCheck.PACK_METADATA));
                output.write(packMetadata.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }

    private static Path jar(String fileNamePrefix) {
        String jars = System.getProperty("plexus.forgeEventJars");
        return Arrays.stream(jars.split(File.pathSeparator))
                .map(Path::of)
                .filter(jar -> jar.getFileName().toString().startsWith(fileNamePrefix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + fileNamePrefix + "* in " + jars));
    }
}
