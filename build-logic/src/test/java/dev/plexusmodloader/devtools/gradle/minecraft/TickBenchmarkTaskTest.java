package dev.plexusmodloader.devtools.gradle.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TickBenchmarkTaskTest {

    @Test
    void percentilesUseTheNearestRank() {
        long[] sorted = new long[100];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = i + 1;
        }
        assertEquals(50, TickBenchmarkTask.percentile(sorted, 50));
        assertEquals(95, TickBenchmarkTask.percentile(sorted, 95));
        assertEquals(99, TickBenchmarkTask.percentile(sorted, 99));
        assertEquals(7, TickBenchmarkTask.percentile(new long[] {7}, 99));
    }

    @Test
    void aFixedHeapSetsTheSameMinimumAndMaximum() {
        assertEquals(List.of(), TickBenchmarkTask.heapArguments(null));
        assertEquals(List.of("-Xms2g", "-Xmx2g"), TickBenchmarkTask.heapArguments("2g"));
        assertEquals(List.of("-Xms2048m", "-Xmx2048m"), TickBenchmarkTask.heapArguments("2048m"));
        assertThrows(GradleException.class, () -> TickBenchmarkTask.heapArguments("2 GB"));
        assertThrows(GradleException.class, () -> TickBenchmarkTask.heapArguments("0g"));
    }

    @Test
    void workloadPackDeclaresTheGamesFormatAndTheHoldTest(@TempDir Path directory) throws Exception {
        Path gameJar = directory.resolve("game.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(gameJar))) {
            jar.putNextEntry(new JarEntry("version.json"));
            jar.write("{\"pack_version\": {\"data_major\": 107, \"data_minor\": 1}}".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        String format = TickBenchmarkTask.packFormat(gameJar);
        assertEquals("\"min_format\": [107, 1], \"max_format\": 107", format);

        Path pack = directory.resolve("pack");
        TickBenchmarkTask.writeWorkload(pack, format, 1200);

        JsonObject meta = JsonParser.parseString(Files.readString(pack.resolve("pack.mcmeta")))
                .getAsJsonObject()
                .getAsJsonObject("pack");
        assertEquals(107, meta.get("max_format").getAsInt());
        JsonObject hold = JsonParser.parseString(
                        Files.readString(pack.resolve("data/plexus_tick_benchmark/test_instance/hold.json")))
                .getAsJsonObject();
        assertEquals(1200, hold.get("setup_ticks").getAsInt());
        assertTrue(hold.get("required").getAsBoolean());
        String tick = Files.readString(pack.resolve("data/plexus_tick_benchmark/function/tick.mcfunction"));
        assertTrue(tick.contains("summon minecraft:pig"));
        // An opaque block would queue lighting work faster than the light engine drains it.
        assertTrue(tick.contains("minecraft:glass replace minecraft:air"));
        assertTrue(!tick.contains("minecraft:stone"), tick);
    }

    @Test
    void runResultSerializesToJsonWithGcAndMemoryMetrics() {
        TickBenchmarkTask.RunResult result = new TickBenchmarkTask.RunResult(
                1000, 10.5, 15.2, 18.0, 35.0, 0.2, 1280.0, 160.0, 15, 250.0, 30.0);
        String json = result.toJson();
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(1000, parsed.get("ticks").getAsInt());
        assertEquals(10.5, parsed.get("p50Millis").getAsDouble());
        assertEquals(1280.0, parsed.get("peakHeapMiB").getAsDouble());
        assertEquals(160.0, parsed.get("peakHeapAfterGcMiB").getAsDouble());
        assertEquals(15, parsed.get("collections").getAsInt());
    }
}
