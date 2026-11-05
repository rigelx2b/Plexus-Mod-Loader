package dev.plexusmodloader.game.layer.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Minecraft 1.20.1 has no pack overlays, so {@link ModPacks} reads {@code pack.mcmeta} itself. It must pick the same
 * entries later versions pick, and ignore entries only 1.21.9 and later can read, which name no {@code formats}.
 */
class ModPacksTest {

    private static final int DATA_FORMAT_1_20_1 = 15;

    private static JsonObject meta(String entries) {
        return JsonParser.parseString(
                        "{\"pack\": {\"description\": \"x\"}, \"overlays\": {\"entries\": [" + entries + "]}}")
                .getAsJsonObject();
    }

    @Test
    void anOverlayWhoseRangeHoldsTheFormatIsUsed() {
        assertEquals(
                List.of("old"),
                ModPacks.overlays(
                        meta("{\"directory\": \"old\", \"formats\": [15, 26]},"
                                + "{\"directory\": \"new\", \"formats\": [121, 121]}"),
                        DATA_FORMAT_1_20_1,
                        "test"));
    }

    @Test
    void theOtherFormsOfFormatsAreRead() {
        assertEquals(
                List.of("number", "object"),
                ModPacks.overlays(
                        meta(
                                "{\"directory\": \"number\", \"formats\": 15},"
                                        + "{\"directory\": \"object\", \"formats\": {\"min_inclusive\": 10, \"max_inclusive\": 15}},"
                                        + "{\"directory\": \"above\", \"formats\": {\"min_inclusive\": 16, \"max_inclusive\": 26}}"),
                        DATA_FORMAT_1_20_1,
                        "test"));
    }

    @Test
    void entriesWithoutFormatsAreForLaterVersions() {
        assertEquals(
                List.of(),
                ModPacks.overlays(
                        meta("{\"directory\": \"later\", \"min_format\": 121, \"max_format\": 121}"),
                        DATA_FORMAT_1_20_1,
                        "test"));
    }

    /** A client has the jar open as its resource pack when it opens the same jar as a data pack. */
    @Test
    void theSameJarCanBeOpenTwiceWithItsOverlays(@TempDir Path folder) throws IOException {
        Path jar = folder.resolve("mod.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("old/data/mod/a.json"));
            zip.write("overlay".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("old/assets/mod/b.json"));
            zip.write("assets".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        List<String> overlays = List.of("old");
        try (PackResources assets = ModPacks.jarPack("assets", jar, overlays, PackType.CLIENT_RESOURCES);
                PackResources data = ModPacks.jarPack("data", jar, overlays, PackType.SERVER_DATA)) {
            assertNotNull(assets.getResource(PackType.CLIENT_RESOURCES, new ResourceLocation("mod", "b.json")));
            assertNotNull(data.getResource(PackType.SERVER_DATA, new ResourceLocation("mod", "a.json")));
        }
    }

    @Test
    void aPackWithoutOverlaysHasNone() {
        JsonObject meta =
                JsonParser.parseString("{\"pack\": {\"description\": \"x\"}}").getAsJsonObject();
        assertEquals(List.of(), ModPacks.overlays(meta, DATA_FORMAT_1_20_1, "test"));
    }
}
