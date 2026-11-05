package dev.plexusmodloader.game.layer.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SDK writes {@code tags/block}, {@code loot_table} and item definitions; 1.20.1 reads {@code tags/blocks},
 * {@code loot_tables} and {@code models/item}. The mod pack answers those names from the later files, and a file under
 * the older name wins.
 */
class LaterFormatPackTest {

    @TempDir
    Path root;

    private PackResources pack() {
        return new LaterFormatPack(new PathPackResources("test", root, false));
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

    private static Map<String, IoSupplier<InputStream>> list(
            PackResources pack, PackType type, String namespace, String path) {
        Map<String, IoSupplier<InputStream>> listed = new HashMap<>();
        pack.listResources(type, namespace, path, (location, resource) -> listed.put(location.toString(), resource));
        return listed;
    }

    /** The game registers an entry of a pack that is not built in as experimental, which asks for a backup on open. */
    @Test
    void aModPackCountsAsBuiltIn() throws IOException {
        try (PackResources pack = pack()) {
            assertTrue(pack.isBuiltin());
        }
    }

    @Test
    void laterDataFoldersAreReadUnderTheirOlderNames() {
        assertEquals(Optional.of("tags/block/mineable/pickaxe.json"), later("tags/blocks/mineable/pickaxe.json"));
        assertEquals(Optional.of("loot_table/blocks/crystal.json"), later("loot_tables/blocks/crystal.json"));
        assertEquals(Optional.of("recipe"), later("recipes"));
        assertEquals(Optional.of("structure/hut.nbt"), later("structures/hut.nbt"));
        assertEquals(Optional.of("tags/function/load.json"), later("tags/functions/load.json"));
    }

    @Test
    void foldersWithTheSameNameInBothAreLeftAlone() {
        assertEquals(Optional.empty(), later("worldgen/biome/plains.json"));
        assertEquals(Optional.empty(), later("tags/worldgen/biome/hot.json"));
        assertEquals(Optional.empty(), later("recipes_extra/x.json"));
    }

    private static Optional<String> later(String path) {
        return LaterFormatPack.laterDataPath(path);
    }

    @Test
    void dataIsFoundAndListedUnderTheOlderFolder() throws IOException {
        write("data/minecraft/tags/block/mineable/pickaxe.json", "later");
        try (PackResources pack = pack()) {
            ResourceLocation older = new ResourceLocation("minecraft", "tags/blocks/mineable/pickaxe.json");
            assertEquals("later", read(pack.getResource(PackType.SERVER_DATA, older)));
            assertEquals(
                    Set.of(older.toString()),
                    list(pack, PackType.SERVER_DATA, "minecraft", "tags/blocks").keySet());
        }
    }

    @Test
    void aFileUnderTheOlderNameWins() throws IOException {
        write("data/mod/loot_table/blocks/a.json", "later");
        write("data/mod/loot_tables/blocks/a.json", "older");
        write("data/mod/loot_table/blocks/b.json", "only later");
        try (PackResources pack = pack()) {
            ResourceLocation a = new ResourceLocation("mod", "loot_tables/blocks/a.json");
            assertEquals("older", read(pack.getResource(PackType.SERVER_DATA, a)));
            Map<String, IoSupplier<InputStream>> listed = list(pack, PackType.SERVER_DATA, "mod", "loot_tables");
            assertEquals(Set.of(a.toString(), "mod:loot_tables/blocks/b.json"), listed.keySet());
            assertEquals("older", read(listed.get(a.toString())));
        }
    }

    @Test
    void anItemDefinitionBecomesAnItemModel() throws IOException {
        write(
                "assets/mod/items/crystal_block.json",
                "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"mod:block/crystal_block\"}}");
        try (PackResources pack = pack()) {
            String model = "{\"parent\":\"mod:block/crystal_block\"}";
            ResourceLocation location = new ResourceLocation("mod", "models/item/crystal_block.json");
            assertEquals(model, read(pack.getResource(PackType.CLIENT_RESOURCES, location)));
            assertEquals(
                    model,
                    read(list(pack, PackType.CLIENT_RESOURCES, "mod", "models").get(location.toString())));
            assertNull(
                    pack.getResource(PackType.CLIENT_RESOURCES, new ResourceLocation("mod", "models/item/none.json")));
        }
    }

    @Test
    void anItemModelFileWinsOverTheDefinition() throws IOException {
        write("assets/mod/models/item/crystal.json", "the file");
        write(
                "assets/mod/items/crystal.json",
                "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"mod:item/crystal\"}}");
        try (PackResources pack = pack()) {
            ResourceLocation location = new ResourceLocation("mod", "models/item/crystal.json");
            assertEquals("the file", read(pack.getResource(PackType.CLIENT_RESOURCES, location)));
            assertEquals(
                    "the file",
                    read(list(pack, PackType.CLIENT_RESOURCES, "mod", "models").get(location.toString())));
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
                IoSupplier<InputStream> resource = pack.getResource(
                        PackType.CLIENT_RESOURCES, new ResourceLocation("mod", "models/item/" + name + ".json"));
                assertNotNull(resource);
                IOException failure = assertThrows(IOException.class, resource::get);
                assertEquals(0, failure.getMessage().indexOf("PLEXUS-RESOURCE-011"), failure.getMessage());
            }
        }
    }
}
