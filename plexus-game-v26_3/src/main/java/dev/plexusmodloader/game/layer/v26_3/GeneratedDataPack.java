package dev.plexusmodloader.game.layer.v26_3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.plexusmodloader.api.content.entity.MobCategory;
import dev.plexusmodloader.api.devtools.gametest.TestArea;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.tag.TagKey;
import dev.plexusmodloader.api.trade.TradeOffer;
import dev.plexusmodloader.api.worldgen.BiomeDefinition;
import dev.plexusmodloader.api.worldgen.BiomeSelector;
import dev.plexusmodloader.api.worldgen.BiomeSpawn;
import dev.plexusmodloader.api.worldgen.FeaturePlacement;
import dev.plexusmodloader.api.worldgen.GenerationStepName;
import dev.plexusmodloader.api.worldgen.StructureDefinition;
import dev.plexusmodloader.game.layer.v26_3.content.Equipment;
import dev.plexusmodloader.game.version.layer.NativeDeclarations;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.BiomeDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.CodeFeatureDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.GameTestDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.ItemDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.StructureDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.TagEntriesDeclaration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    static Optional<Written> write(NativeDeclarations declarations) throws IOException {
        Path root = Path.of(".plexus", "generated-pack").toAbsolutePath();
        deleteRecursively(root);
        List<GameTestDeclaration> tests = declarations.gameTests();
        List<BiomeDeclaration> biomes = declarations.biomes();
        List<CodeFeatureDeclaration> features = declarations.codeFeatures();
        List<StructureDeclaration> structures = declarations.structures();
        Map<TagKey, List<ResourceId>> tags = tagFiles(withRepairTags(declarations));
        List<NativeDeclarations.VillagerTradeDeclaration> villagerTrades = declarations.villagerTrades();
        List<NativeDeclarations.WanderingTradeDeclaration> wanderingTrades = declarations.wanderingTrades();
        if (tests.isEmpty()
                && biomes.isEmpty()
                && features.isEmpty()
                && structures.isEmpty()
                && tags.isEmpty()
                && villagerTrades.isEmpty()
                && wanderingTrades.isEmpty()) {
            return Optional.empty();
        }
        int hash = 1;
        for (GameTestDeclaration test : tests) {
            String json = """
                    {
                      "type": "minecraft:function",
                      "environment": "minecraft:default",
                      "function": "%s",
                      "max_ticks": %d,
                      "required": true,
                      "setup_ticks": 1,
                      "structure": "%s"
                    }
                    """.formatted(test.id(), test.maxTicks(), structure(root, test.area()));
            hash = 31 * hash + writeFile(root, test.id(), "test_instance", json);
        }
        for (BiomeDeclaration biome : biomes) {
            hash = 31 * hash
                    + writeFile(root, biome.id(), "worldgen/biome", GSON.toJson(biomeJson(biome.definition())));
        }
        for (CodeFeatureDeclaration feature : features) {
            JsonObject configured = new JsonObject();
            configured.addProperty("type", feature.id().toString());
            hash = 31 * hash + writeFile(root, feature.id(), "worldgen/feature", GSON.toJson(configured));
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
        hash = 31 * hash + writeTrades(root, villagerTrades, wanderingTrades, false);
        return Optional.of(new Written(root, Integer.toHexString(hash)));
    }

    /**
     * The tag entries mods declared, plus one generated item tag per repair item of a tool or armour material: from 1.21.11 the
     * game names repair items by tag ({@code Equipment.repairTagId}).
     */
    static List<TagEntriesDeclaration> withRepairTags(NativeDeclarations declarations) {
        List<TagEntriesDeclaration> all = new ArrayList<>(declarations.tagEntries());
        for (ItemDeclaration item : declarations.items()) {
            Optional<ResourceId> repair =
                    item.definition().tool().flatMap(tool -> tool.material().repairItem());
            if (repair.isEmpty()) {
                repair = item.definition()
                        .armor()
                        .flatMap(armor -> armor.material().repairItem());
            }
            repair.ifPresent(id -> all.add(
                    new TagEntriesDeclaration(item.ownerModId(), TagKey.item(Equipment.repairTagId(id)), List.of(id))));
        }
        return all;
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

    /**
     * The trades mods added, as data: one {@code villager_trade} file per trade and the entries of the tags that the
     * game's trade sets read ({@code #minecraft:farmer/level_1}, {@code #minecraft:wandering_trader/common}). Tags add
     * to the game's own, so the new trades join the pools the game draws from.
     *
     * <p>The pack is first written before the game's registries exist, so every trade is written then. When the game
     * loads its packs the registries are complete, and {@link #checkTrades} writes the files again without the trades
     * whose profession or item is not registered: the game refuses to load a data pack with a trade of an unknown item.
     *
     * @param validate {@code true} to drop (and delete the files of) trades with an unregistered profession or item
     */
    private static int writeTrades(
            Path root,
            List<NativeDeclarations.VillagerTradeDeclaration> villager,
            List<NativeDeclarations.WanderingTradeDeclaration> wandering,
            boolean validate)
            throws IOException {
        int hash = 1;
        Map<ResourceId, List<ResourceId>> tags = new LinkedHashMap<>();
        int index = 0;
        for (NativeDeclarations.VillagerTradeDeclaration trade : villager) {
            ResourceId id = ResourceId.of(trade.ownerModId(), "plexus_villager_trade_" + index++);
            ResourceId profession = trade.profession();
            boolean valid =
                    !validate || (professionExists(profession) && tradeItemsExist(trade.ownerModId(), trade.offer()));
            if (!valid) {
                if (professionExists(profession)) {
                    // the items were reported by tradeItemsExist
                } else {
                    reportOnce("PLEXUS-TRADE-001 mod " + trade.ownerModId() + " adds a trade to profession "
                            + profession + ", which is not registered; skipped");
                }
                deleteTrade(root, id);
                continue;
            }
            hash = 31 * hash + writeFile(root, id, "villager_trade", GSON.toJson(tradeJson(trade.offer())));
            tags.computeIfAbsent(
                            ResourceId.of(profession.namespace(), profession.path() + "/level_" + trade.level()),
                            tag -> new ArrayList<>())
                    .add(id);
        }
        for (NativeDeclarations.WanderingTradeDeclaration trade : wandering) {
            ResourceId id = ResourceId.of(trade.ownerModId(), "plexus_wandering_trade_" + index++);
            if (validate && !tradeItemsExist(trade.ownerModId(), trade.offer())) {
                deleteTrade(root, id);
                continue;
            }
            hash = 31 * hash + writeFile(root, id, "villager_trade", GSON.toJson(tradeJson(trade.offer())));
            tags.computeIfAbsent(
                            ResourceId.minecraft("wandering_trader/" + (trade.rare() ? "uncommon" : "common")),
                            tag -> new ArrayList<>())
                    .add(id);
        }
        for (Map.Entry<ResourceId, List<ResourceId>> tag : tags.entrySet()) {
            hash = 31 * hash
                    + writeFile(root, tag.getKey(), "tags/villager_trade", GSON.toJson(tagJson(tag.getValue())));
        }
        if (validate) {
            // A tag whose trades were all dropped must not stay on disk from the first write.
            for (NativeDeclarations.VillagerTradeDeclaration trade : villager) {
                ResourceId tag = ResourceId.of(
                        trade.profession().namespace(), trade.profession().path() + "/level_" + trade.level());
                if (!tags.containsKey(tag)) {
                    Files.deleteIfExists(tradeFile(root, tag, "tags/villager_trade"));
                }
            }
            for (String pool : List.of("common", "uncommon")) {
                if (!tags.containsKey(ResourceId.minecraft("wandering_trader/" + pool))) {
                    Files.deleteIfExists(
                            tradeFile(root, ResourceId.minecraft("wandering_trader/" + pool), "tags/villager_trade"));
                }
            }
        }
        return hash;
    }

    /**
     * Rewrites the trade files of the generated pack without the trades whose profession or item is not registered
     * (logged once with {@code PLEXUS-TRADE-001}). Called when the game loads its packs, after its registries are
     * complete.
     */
    static void checkTrades(NativeDeclarations declarations, Path root) {
        if (declarations.villagerTrades().isEmpty()
                && declarations.wanderingTrades().isEmpty()) {
            return;
        }
        try {
            writeTrades(root, declarations.villagerTrades(), declarations.wanderingTrades(), true);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Plexus Mod Loader could not check its generated trades", e);
        }
    }

    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void reportOnce(String message) {
        if (REPORTED.add(message)) {
            org.slf4j.LoggerFactory.getLogger("Plexus Mod Loader").warn("[Plexus Mod Loader] {}", message);
        }
    }

    private static boolean professionExists(ResourceId profession) {
        return net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.containsKey(Views.id(profession));
    }

    private static boolean tradeItemsExist(String modId, TradeOffer offer) {
        List<ResourceId> items = new ArrayList<>();
        items.add(offer.buy().item());
        offer.secondBuy().ifPresent(second -> items.add(second.item()));
        items.add(offer.sell().item());
        for (ResourceId item : items) {
            if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(Views.id(item))) {
                reportOnce("PLEXUS-TRADE-001 mod " + modId + " has a trade with item " + item
                        + ", which is not registered; skipped");
                return false;
            }
        }
        return true;
    }

    private static Path tradeFile(Path root, ResourceId id, String directory) {
        return root.resolve("data").resolve(id.namespace()).resolve(directory).resolve(id.path() + ".json");
    }

    private static void deleteTrade(Path root, ResourceId id) throws IOException {
        Files.deleteIfExists(tradeFile(root, id, "villager_trade"));
    }

    /** A {@code villager_trade} file (verified against the game's own files in the client jar). */
    static JsonObject tradeJson(TradeOffer offer) {
        JsonObject json = new JsonObject();
        json.add("wants", tradeStack(offer.buy()));
        offer.secondBuy().ifPresent(second -> json.add("additional_wants", tradeStack(second)));
        json.add("gives", tradeStack(offer.sell()));
        json.addProperty("max_uses", offer.maxUses());
        json.addProperty("xp", offer.experience());
        json.addProperty("reputation_discount", offer.priceMultiplier());
        return json;
    }

    private static JsonObject tradeStack(dev.plexusmodloader.api.inventory.ItemStackView stack) {
        JsonObject json = new JsonObject();
        json.addProperty("id", stack.item().toString());
        json.addProperty("count", stack.count());
        return json;
    }

    /** The empty structure of a test area: the game's own for one block, else one Plexus Mod Loader writes. */
    private static String structure(Path root, TestArea area) throws IOException {
        if (area.equals(TestArea.SINGLE_BLOCK)) {
            return "minecraft:empty";
        }
        String name = "empty_" + area.width() + "x" + area.height() + "x" + area.depth();
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
                    SharedConstants.getCurrentVersion().dataVersion().version());
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
        JsonObject attributes = new JsonObject();
        attributes.addProperty("minecraft:visual/sky_color", color(definition.skyColor()));
        definition.fogColor().ifPresent(color -> attributes.addProperty("minecraft:visual/fog_color", color(color)));
        attributes.add("minecraft:gameplay/natural_mob_spawns", naturalMobSpawnsJson(definition.spawns()));
        json.add("attributes", attributes);
        json.add("carvers", new JsonArray());
        json.addProperty("downfall", definition.downfall());
        JsonObject effects = new JsonObject();
        effects.addProperty("water_color", color(definition.waterColor()));
        definition.grassColor().ifPresent(color -> effects.addProperty("grass_color", color(color)));
        definition.foliageColor().ifPresent(color -> effects.addProperty("foliage_color", color(color)));
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
        json.addProperty("temperature", definition.temperature());
        return json;
    }

    /**
     * A biome's spawns as its natural mob spawns attribute, as in {@code data/minecraft/worldgen/biome/plains.json}.
     * Every spawn group is listed, empty ones too, so the biome has exactly the declared spawns whatever the
     * dimension sets (as the game's {@code the_void.json} does).
     */
    private static JsonObject naturalMobSpawnsJson(List<BiomeSpawn> spawns) {
        JsonObject byCategory = new JsonObject();
        SPAWN_GROUPS.forEach(group -> byCategory.add(group, new JsonArray()));
        for (BiomeSpawn spawn : spawns) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", spawn.type().toString());
            if (spawn.minGroup() == spawn.maxGroup()) {
                entry.addProperty("count", spawn.minGroup());
            } else {
                JsonObject count = new JsonObject();
                count.addProperty("type", "minecraft:uniform");
                count.addProperty("max_inclusive", spawn.maxGroup());
                count.addProperty("min_inclusive", spawn.minGroup());
                entry.add("count", count);
            }
            entry.addProperty("weight", spawn.weight());
            byCategory.getAsJsonArray(group(spawn.category())).add(entry);
        }
        JsonObject argument = new JsonObject();
        argument.add("spawn_costs", new JsonObject());
        argument.add("spawns_by_category", byCategory);
        JsonObject json = new JsonObject();
        json.add("argument", argument);
        json.addProperty("modifier", "overlay");
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

    private static String color(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
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
