package dev.plexusmodloader.game.layer.v1_21_1;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.plexusmodloader.api.content.entity.MobCategory;
import dev.plexusmodloader.api.devtools.gametest.TestArea;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.tag.TagKey;
import dev.plexusmodloader.api.worldgen.BiomeDefinition;
import dev.plexusmodloader.api.worldgen.BiomeSelector;
import dev.plexusmodloader.api.worldgen.BiomeSpawn;
import dev.plexusmodloader.api.worldgen.FeaturePlacement;
import dev.plexusmodloader.api.worldgen.GenerationStepName;
import dev.plexusmodloader.api.worldgen.StructureDefinition;
import dev.plexusmodloader.game.version.layer.NativeDeclarations;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.BiomeDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.CodeFeatureDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.GameTestDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.StructureDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.TagEntriesDeclaration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.levelgen.GenerationStep;

/**
 * Writes the data Plexus Mod Loader derives from declarations: one {@code test_instance} per registered game test,
 * in the format of the game's own {@code minecraft:always_pass}, one biome file per biome defined in
 * code, and one tag file per tag that mods add entries to from code. The directory is recreated on every start under the game directory
 * ({@code .plexus/generated-pack}), so it can be inspected.
 */
final class GeneratedDataPack {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> SPAWN_GROUPS = List.of(
            "ambient",
            "axolotls",
            "creature",
            "misc",
            "monster",
            "underground_water_creature",
            "water_ambient",
            "water_creature");

    private GeneratedDataPack() {}

    /**
     * The written pack.
     *
     * @param root pack directory
     * @param version changes whenever any generated file changes, so clients and servers with
     *     different content do not treat it as the same known pack
     */
    record Written(Path root, String version) {}

    /**
     * @param gameTestServer whether this is the game test server, which always needs the empty structure of the
     *     built-in {@code minecraft:always_pass} test
     */
    static Optional<Written> write(NativeDeclarations declarations, boolean gameTestServer) throws IOException {
        Path root = Path.of(".plexus", "generated-pack").toAbsolutePath();
        deleteRecursively(root);
        List<GameTestDeclaration> tests = declarations.gameTests();
        List<BiomeDeclaration> biomes = declarations.biomes();
        List<CodeFeatureDeclaration> features = declarations.codeFeatures();
        List<StructureDeclaration> structures = declarations.structures();
        Map<TagKey, List<ResourceId>> tags = tagFiles(declarations.tagEntries());
        if (tests.isEmpty()
                && biomes.isEmpty()
                && features.isEmpty()
                && structures.isEmpty()
                && tags.isEmpty()
                && !gameTestServer) {
            return Optional.empty();
        }
        int hash = 1;
        // 1.21.1 has no data-driven test instances: its tests are TestFunctions (GameTests) that name a structure
        // template, so the pack only carries the empty structure of each test area.
        Set<TestArea> areas = new LinkedHashSet<>();
        if (gameTestServer) {
            areas.add(TestArea.SINGLE_BLOCK);
        }
        for (GameTestDeclaration test : tests) {
            areas.add(test.area());
        }
        for (TestArea area : areas) {
            hash = 31 * hash + structure(root, area).hashCode();
        }
        for (BiomeDeclaration biome : biomes) {
            hash = 31 * hash
                    + writeFile(root, biome.id(), "worldgen/biome", GSON.toJson(biomeJson(biome.definition())));
        }
        for (CodeFeatureDeclaration feature : features) {
            JsonObject configured = new JsonObject();
            configured.addProperty("type", feature.id().toString());
            configured.add("config", new JsonObject());
            hash = 31 * hash + writeFile(root, feature.id(), "worldgen/configured_feature", GSON.toJson(configured));
            hash = 31 * hash
                    + writeFile(
                            root,
                            feature.id(),
                            "worldgen/placed_feature",
                            GSON.toJson(placedFeatureJson(feature.id(), feature.placement())));
        }
        for (StructureDeclaration structure : structures) {
            hash = 31 * hash
                    + writeFile(
                            root,
                            structure.id(),
                            "worldgen/structure",
                            GSON.toJson(structureJson(structure.id(), structure.definition())));
            hash = 31 * hash
                    + writeFile(
                            root,
                            structure.id(),
                            "worldgen/structure_set",
                            GSON.toJson(structureSetJson(structure.id(), structure.definition())));
        }
        for (Map.Entry<TagKey, List<ResourceId>> tag : tags.entrySet()) {
            hash = 31 * hash
                    + writeFile(
                            root,
                            tag.getKey().id(),
                            "tags/" + tag.getKey().registry().directory(),
                            GSON.toJson(tagJson(tag.getValue())));
        }
        return Optional.of(new Written(root, Integer.toHexString(hash)));
    }

    /** The entries every mod added to each tag from code, in declaration order, without repeats. */
    static Map<TagKey, List<ResourceId>> tagFiles(List<TagEntriesDeclaration> declarations) {
        Map<TagKey, Set<ResourceId>> merged = new LinkedHashMap<>();
        for (TagEntriesDeclaration declaration : declarations) {
            merged.computeIfAbsent(declaration.tag(), tag -> new LinkedHashSet<>())
                    .addAll(declaration.entries());
        }
        Map<TagKey, List<ResourceId>> files = new LinkedHashMap<>();
        merged.forEach((tag, entries) -> files.put(tag, List.copyOf(entries)));
        return files;
    }

    /**
     * A tag file that adds to the tag ({@code replace: false}). Entries are optional, so one that is
     * not registered (reported as {@code PLEXUS-CONTENT-007}) does not keep the whole tag from loading.
     */
    static JsonObject tagJson(List<ResourceId> entries) {
        JsonObject json = new JsonObject();
        json.addProperty("replace", false);
        JsonArray values = new JsonArray();
        for (ResourceId entry : entries) {
            JsonObject value = new JsonObject();
            value.addProperty("id", entry.toString());
            value.addProperty("required", false);
            values.add(value);
        }
        json.add("values", values);
        return json;
    }

    /** The empty structure of a test area, which Plexus Mod Loader writes (1.21.1 has no empty test structure). */
    private static String structure(Path root, TestArea area) throws IOException {
        String name = dev.plexusmodloader.game.layer.v1_21_1.gametest.GameTests.structureName(area)
                .substring("plexus:".length());
        Path file = root.resolve("data").resolve("plexus").resolve("structure").resolve(name + ".nbt");
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            CompoundTag structure = new CompoundTag();
            ListTag size = new ListTag();
            size.add(IntTag.valueOf(area.width()));
            size.add(IntTag.valueOf(area.height()));
            size.add(IntTag.valueOf(area.depth()));
            structure.put("size", size);
            structure.put("blocks", new ListTag());
            structure.put("palette", new ListTag());
            structure.put("entities", new ListTag());
            // Plexus Mod Loader starts the game layer before the game reads its version file.
            SharedConstants.tryDetectVersion();
            structure.putInt(
                    "DataVersion",
                    SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            NbtIo.writeCompressed(structure, file);
        }
        return "plexus:" + name;
    }

    private static int writeFile(Path root, ResourceId id, String directory, String json) throws IOException {
        Path file =
                root.resolve("data").resolve(id.namespace()).resolve(directory).resolve(id.path() + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return (file.getFileName() + json).hashCode();
    }

    /** The game's biome file format, as in {@code data/minecraft/worldgen/biome/plains.json}. */
    static JsonObject biomeJson(BiomeDefinition definition) {
        JsonObject json = new JsonObject();
        // 1.21.1 keeps carvers per carving step (a flat list only from 1.21.2); none here.
        json.add("carvers", new JsonObject());
        json.addProperty("downfall", definition.downfall());
        // 1.21.1 keeps the colours in the biome's effects, as integers; sky, fog, water and water fog are required.
        JsonObject effects = new JsonObject();
        effects.addProperty("sky_color", definition.skyColor() & 0xFFFFFF);
        effects.addProperty("fog_color", definition.fogColor().orElse(0xC0D8FF) & 0xFFFFFF);
        effects.addProperty("water_color", definition.waterColor() & 0xFFFFFF);
        effects.addProperty("water_fog_color", definition.waterColor() & 0xFFFFFF);
        definition.grassColor().ifPresent(color -> effects.addProperty("grass_color", color & 0xFFFFFF));
        definition.foliageColor().ifPresent(color -> effects.addProperty("foliage_color", color & 0xFFFFFF));
        json.add("effects", effects);
        JsonArray features = new JsonArray();
        for (int step = 0; step < GenerationStep.Decoration.values().length; step++) {
            features.add(new JsonArray());
        }
        definition.features().forEach((step, ids) -> {
            JsonArray inStep = features.get(stepIndex(step)).getAsJsonArray();
            ids.forEach(id -> inStep.add(id.toString()));
        });
        json.add("features", features);
        json.addProperty("has_precipitation", definition.precipitation());
        json.add("spawn_costs", new JsonObject());
        JsonObject spawners = new JsonObject();
        SPAWN_GROUPS.forEach(group -> spawners.add(group, new JsonArray()));
        for (BiomeSpawn spawn : definition.spawns()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", spawn.type().toString());
            entry.addProperty("maxCount", spawn.maxGroup());
            entry.addProperty("minCount", spawn.minGroup());
            entry.addProperty("weight", spawn.weight());
            spawners.getAsJsonArray(group(spawn.category())).add(entry);
        }
        json.add("spawners", spawners);
        json.addProperty("temperature", definition.temperature());
        return json;
    }

    /** The game's placed feature format, as in {@code data/minecraft/worldgen/placed_feature/ore_gold_lower.json}. */
    static JsonObject placedFeatureJson(ResourceId feature, FeaturePlacement placement) {
        JsonArray modifiers = new JsonArray();
        if (placement.rarity() > 1) {
            modifiers.add(
                    modifier("minecraft:rarity_filter", object -> object.addProperty("chance", placement.rarity())));
        }
        if (placement.countPerChunk() > 1) {
            modifiers.add(
                    modifier("minecraft:count", object -> object.addProperty("count", placement.countPerChunk())));
        }
        modifiers.add(modifier("minecraft:in_square", object -> {}));
        if (placement.onSurface()) {
            modifiers.add(
                    modifier("minecraft:heightmap", object -> object.addProperty("heightmap", "WORLD_SURFACE_WG")));
        } else {
            modifiers.add(modifier("minecraft:height_range", object -> {
                JsonObject height = new JsonObject();
                height.addProperty("type", "minecraft:uniform");
                height.add("min_inclusive", absolute(placement.minY()));
                height.add("max_inclusive", absolute(placement.maxY()));
                object.add("height", height);
            }));
        }
        modifiers.add(modifier("minecraft:biome", object -> {}));
        JsonObject json = new JsonObject();
        json.addProperty("feature", feature.toString());
        json.add("placement", modifiers);
        return json;
    }

    /** The game's structure format, as in {@code data/minecraft/worldgen/structure/igloo.json}. */
    static JsonObject structureJson(ResourceId id, StructureDefinition definition) {
        JsonObject json = new JsonObject();
        json.addProperty("type", id.toString());
        switch (definition.biomes()) {
            case BiomeSelector.ByTag tag -> json.addProperty("biomes", "#" + tag.tag());
            case BiomeSelector.ById ids -> {
                JsonArray list = new JsonArray();
                ids.ids().stream().map(ResourceId::toString).sorted().forEach(list::add);
                json.add("biomes", list);
            }
        }
        json.add("spawn_overrides", new JsonObject());
        json.addProperty("step", definition.step().name().toLowerCase(Locale.ROOT));
        if (definition.adaptTerrain()) {
            json.addProperty("terrain_adaptation", "beard_thin");
        }
        return json;
    }

    /** The game's structure set format, as in {@code data/minecraft/worldgen/structure_set/igloos.json}. */
    static JsonObject structureSetJson(ResourceId id, StructureDefinition definition) {
        JsonObject placement = new JsonObject();
        placement.addProperty("type", "minecraft:random_spread");
        placement.addProperty("salt", id.toString().hashCode() & 0x7FFFFFFF);
        placement.addProperty("separation", definition.separation());
        placement.addProperty("spacing", definition.spacing());
        JsonObject entry = new JsonObject();
        entry.addProperty("structure", id.toString());
        entry.addProperty("weight", 1);
        JsonArray structures = new JsonArray();
        structures.add(entry);
        JsonObject json = new JsonObject();
        json.add("placement", placement);
        json.add("structures", structures);
        return json;
    }

    private static JsonObject modifier(String type, java.util.function.Consumer<JsonObject> fields) {
        JsonObject modifier = new JsonObject();
        modifier.addProperty("type", type);
        fields.accept(modifier);
        return modifier;
    }

    private static JsonObject absolute(int y) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("absolute", y);
        return anchor;
    }

    private static int stepIndex(GenerationStepName step) {
        return GenerationStep.Decoration.valueOf(step.name()).ordinal();
    }

    private static String group(MobCategory category) {
        return category.name().toLowerCase(Locale.ROOT);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
