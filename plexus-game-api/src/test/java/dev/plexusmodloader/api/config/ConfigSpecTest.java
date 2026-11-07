package dev.plexusmodloader.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigSpecTest {

    enum Mode {
        SOFT,
        HARD
    }

    @TempDir
    Path folder;

    private static final class Values {
        final ConfigSpec.Value<Boolean> enabled;
        final ConfigSpec.Value<Integer> radius;
        final ConfigSpec.Value<Double> chance;
        final ConfigSpec.Value<String> greeting;
        final ConfigSpec.Value<Mode> mode;
        final ConfigSpec spec;

        Values() {
            ConfigSpec.Builder builder = ConfigSpec.builder();
            enabled = builder.comment("Turns the mod on.").booleanValue("enabled", true);
            radius = builder.intValue("radius", 8, 1, 32);
            chance = builder.doubleValue("chance", 0.25, 0, 1);
            greeting = builder.stringValue("greeting", " Hello = World #1");
            mode = builder.enumValue("mode", Mode.SOFT);
            spec = builder.build();
        }
    }

    @Test
    void missingFileIsWrittenWithDefaults() throws IOException {
        Values values = new Values();
        Path file = folder.resolve("sub").resolve("mod.properties");

        List<String> warnings = values.spec.load(file);

        assertTrue(warnings.isEmpty());
        assertTrue(Files.exists(file));
        assertEquals(8, values.radius.get());
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("# Turns the mod on."));
        assertTrue(text.contains("# default: 8, range: 1 to 32"));
        assertTrue(text.contains("# default: SOFT, one of: SOFT, HARD"));
    }

    @Test
    void writtenFileReadsBackWithSpecialCharacters() throws IOException {
        Values writer = new Values();
        Path file = folder.resolve("mod.properties");
        writer.spec.load(file);

        Values reader = new Values();
        List<String> warnings = reader.spec.load(file);

        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(" Hello = World #1", reader.greeting.get());
    }

    @Test
    void readsValuesCaseInsensitivelyForEnumsAndBooleans() throws IOException {
        Path file = folder.resolve("mod.properties");
        Files.writeString(file, "enabled=FALSE\nradius=12\nchance=0.5\ngreeting=Hi\nmode=hard\n");
        Values values = new Values();

        List<String> warnings = values.spec.load(file);

        assertTrue(warnings.isEmpty(), warnings.toString());
        assertFalse(values.enabled.get());
        assertEquals(12, values.radius.get());
        assertEquals(0.5, values.chance.get());
        assertEquals("Hi", values.greeting.get());
        assertEquals(Mode.HARD, values.mode.get());
    }

    @Test
    void invalidMissingAndUnknownEntriesAreCorrectedAndReported() throws IOException {
        Path file = folder.resolve("mod.properties");
        Files.writeString(file, "enabled=maybe\nradius=99\nchance=abc\nmode=medium\nleftover=1\n");
        Values values = new Values();

        List<String> warnings = values.spec.load(file);

        assertEquals(6, warnings.size(), warnings.toString());
        assertTrue(values.enabled.get());
        assertEquals(8, values.radius.get());
        assertEquals(0.25, values.chance.get());
        assertEquals(Mode.SOFT, values.mode.get());
        String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(rewritten.contains("leftover"));
        assertTrue(rewritten.contains("radius=8"));
        assertTrue(new Values().spec.load(file).isEmpty());
    }

    @Test
    void loadAgainPicksUpEditsAndKeepsLiveValueObjects() throws IOException {
        Path file = folder.resolve("mod.properties");
        Values values = new Values();
        values.spec.load(file);
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8).replace("radius=8", "radius=3"));

        values.spec.load(file);

        assertEquals(3, values.radius.get());
        assertEquals(List.of("enabled", "radius", "chance", "greeting", "mode"), values.spec.keys());
    }

    @Test
    void builderRejectsBadKeysRangesAndDuplicates() {
        ConfigSpec.Builder builder = ConfigSpec.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.booleanValue("has space", true));
        assertThrows(IllegalArgumentException.class, () -> builder.intValue("n", 50, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> builder.doubleValue("d", 2, 0, 1));
        builder.booleanValue("once", true);
        assertThrows(IllegalArgumentException.class, () -> builder.booleanValue("once", false));
    }
}
