package dev.plexusmodloader.game.layer.v1_21_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SDK writes item definitions; 1.21.1 reads {@code models/item}. The mod pack answers that name from the
 * definition, and a model file under that name wins.
 */
class LaterFormatPackTest {

    @TempDir
    Path root;

    private PackResources pack() {
        return new LaterFormatPack(new PathPackResources(
                new PackLocationInfo("test", Component.literal("test"), PackSource.BUILT_IN, Optional.empty()), root));
    }

    private void write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String read(IoSupplier<InputStream> resource) throws IOException {
        assertNotNull(resource);
        try (InputStream input = resource.get()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static Map<String, IoSupplier<InputStream>> list(
            PackResources pack, PackType type, String namespace, String path) {
        Map<String, IoSupplier<InputStream>> listed = new HashMap<>();
        pack.listResources(type, namespace, path, (location, resource) -> listed.put(location.toString(), resource));
        return listed;
    }

    @Test
    void anItemDefinitionBecomesAnItemModel() throws IOException {
        write(
                "assets/mod/items/crystal_block.json",
                "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"mod:block/crystal_block\"}}");
        try (PackResources pack = pack()) {
            String model = "{\"parent\":\"mod:block/crystal_block\"}";
            ResourceLocation location = id("mod", "models/item/crystal_block.json");
            assertEquals(model, read(pack.getResource(PackType.CLIENT_RESOURCES, location)));
            assertEquals(
                    model,
                    read(list(pack, PackType.CLIENT_RESOURCES, "mod", "models").get(location.toString())));
            assertNull(pack.getResource(PackType.CLIENT_RESOURCES, id("mod", "models/item/none.json")));
        }
    }

    @Test
    void anItemModelFileWinsOverTheDefinition() throws IOException {
        write("assets/mod/models/item/crystal.json", "the file");
        write(
                "assets/mod/items/crystal.json",
                "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"mod:item/crystal\"}}");
        try (PackResources pack = pack()) {
            ResourceLocation location = id("mod", "models/item/crystal.json");
            assertEquals("the file", read(pack.getResource(PackType.CLIENT_RESOURCES, location)));
            assertEquals(
                    "the file",
                    read(list(pack, PackType.CLIENT_RESOURCES, "mod", "models").get(location.toString())));
        }
    }

    @Test
    void dataIsLeftAlone() throws IOException {
        write("data/mod/items/crystal.json", "{}");
        try (PackResources pack = pack()) {
            assertNull(pack.getResource(PackType.SERVER_DATA, id("mod", "models/item/crystal.json")));
            assertEquals(0, list(pack, PackType.SERVER_DATA, "mod", "models").size());
        }
    }

    @Test
    void aDefinitionThatChoosesShowsItsFirstModel() {
        assertEquals(
                Optional.of("mod:item/on"),
                LaterFormatPack.definitionModel(JsonParser.parseString(
                        "{\"model\": {\"type\": \"minecraft:condition\", \"property\": \"minecraft:using_item\","
                                + " \"on_true\": {\"type\": \"minecraft:model\", \"model\": \"mod:item/on\"},"
                                + " \"on_false\": {\"type\": \"minecraft:model\", \"model\": \"mod:item/off\"}}}")));
        assertEquals(
                Optional.of("mod:item/base"),
                LaterFormatPack.definitionModel(JsonParser.parseString(
                        "{\"model\": {\"type\": \"minecraft:special\", \"base\": \"mod:item/base\", \"model\": {}}}")));
    }

    @Test
    void aDefinitionWithoutAModelIsReportedWhenRead() throws IOException {
        write("assets/mod/items/empty.json", "{\"model\": {\"type\": \"minecraft:empty\"}}");
        write(
                "assets/mod/items/self.json",
                "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"mod:item/self\"}}");
        write("assets/mod/items/broken.json", "{");
        try (PackResources pack = pack()) {
            for (String name : new String[] {"empty", "self", "broken"}) {
                IoSupplier<InputStream> resource =
                        pack.getResource(PackType.CLIENT_RESOURCES, id("mod", "models/item/" + name + ".json"));
                assertNotNull(resource);
                IOException failure = assertThrows(IOException.class, resource::get);
                assertEquals(0, failure.getMessage().indexOf("PLEXUS-RESOURCE-011"), failure.getMessage());
            }
        }
    }
}
