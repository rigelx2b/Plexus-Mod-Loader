package dev.plexusmodloader.game.layer.v1_20_4;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * A mod's pack as Minecraft 1.20.4 reads it. One mod jar runs on every version it lists, and the SDK's
 * {@code plexusResources} writes the layout of later versions, which 1.20.4 does not look at: it names data folders in
 * the plural ({@code tags/blocks}, {@code loot_tables}, ...) and finds an item's model at {@code models/item/<name>.json}
 * because it has no item definitions ({@code items/<name>.json}). This pack answers those names from the later files.
 *
 * <p>A file under the name 1.20.4 reads always wins, so a mod can still carry its own copy of a file, in the jar or in
 * an overlay, where the format differs too. Only the folder changes here, not what is in the file: a recipe in a later
 * format still fails to parse on 1.20.4 and needs such a copy.
 */
final class LaterFormatPack implements PackResources {

    /** What 1.20.4 calls a data folder (read from its classes), to what later versions call it. */
    private static final Map<String, String> DATA_FOLDERS = Map.ofEntries(
            Map.entry("advancements", "advancement"),
            Map.entry("functions", "function"),
            Map.entry("item_modifiers", "item_modifier"),
            Map.entry("loot_tables", "loot_table"),
            Map.entry("predicates", "predicate"),
            Map.entry("recipes", "recipe"),
            Map.entry("structures", "structure"),
            Map.entry("tags/blocks", "tags/block"),
            Map.entry("tags/entity_types", "tags/entity_type"),
            Map.entry("tags/fluids", "tags/fluid"),
            Map.entry("tags/functions", "tags/function"),
            Map.entry("tags/game_events", "tags/game_event"),
            Map.entry("tags/items", "tags/item"));

    private static final String ITEM_MODELS = "models/item/";
    private static final String ITEM_DEFINITIONS = "items/";

    private final PackResources pack;

    LaterFormatPack(PackResources pack) {
        this.pack = pack;
    }

    /** The later name of a data path, if its folder has one. */
    static Optional<String> laterDataPath(String path) {
        for (Map.Entry<String, String> folder : DATA_FOLDERS.entrySet()) {
            if (path.equals(folder.getKey()) || path.startsWith(folder.getKey() + "/")) {
                return Optional.of(
                        folder.getValue() + path.substring(folder.getKey().length()));
            }
        }
        return Optional.empty();
    }

    /**
     * The model an item definition shows: its {@code minecraft:model}, or for one that chooses between models (by
     * property, condition or range) the first it names, since 1.20.4's item model is a single one.
     */
    static Optional<String> definitionModel(JsonElement definition) {
        if (definition instanceof JsonArray array) {
            for (JsonElement element : array) {
                Optional<String> model = definitionModel(element);
                if (model.isPresent()) {
                    return model;
                }
            }
            return Optional.empty();
        }
        if (!(definition instanceof JsonObject object)) {
            return Optional.empty();
        }
        String type = text(object.get("type")).orElse("");
        if (type.equals("minecraft:model") || type.equals("model")) {
            return text(object.get("model"));
        }
        if (type.equals("minecraft:special") || type.equals("special")) {
            return text(object.get("base"));
        }
        for (Map.Entry<String, JsonElement> member : object.entrySet()) {
            Optional<String> model = definitionModel(member.getValue());
            if (model.isPresent()) {
                return model;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> text(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString()
                ? Optional.of(primitive.getAsString())
                : Optional.empty();
    }

    /** The JSON of the item model that shows what {@code definition} shows. */
    static String itemModelJson(ResourceLocation model, JsonElement definition) throws IOException {
        String parent = definitionModel(definition)
                .orElseThrow(() -> new IOException("PLEXUS-RESOURCE-011 the item definition of " + model
                        + " names no model that this game version can show"));
        String path = model.getPath();
        String self = model.getNamespace() + ":" + path.substring("models/".length(), path.length() - ".json".length());
        if (parent.equals(self)) {
            throw new IOException("PLEXUS-RESOURCE-011 the item definition of " + model + " shows " + parent
                    + ", which has no model file");
        }
        JsonObject json = new JsonObject();
        json.addProperty("parent", parent);
        return json.toString();
    }

    private static IoSupplier<InputStream> itemModel(ResourceLocation model, IoSupplier<InputStream> definition) {
        return () -> {
            JsonElement json;
            try (Reader reader = new InputStreamReader(definition.get(), StandardCharsets.UTF_8)) {
                json = JsonParser.parseReader(reader);
            } catch (JsonParseException e) {
                throw new IOException("PLEXUS-RESOURCE-011 the item definition of " + model + " is not valid JSON", e);
            }
            return new ByteArrayInputStream(itemModelJson(model, json).getBytes(StandardCharsets.UTF_8));
        };
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        return pack.getRootResource(path);
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        IoSupplier<InputStream> resource = pack.getResource(type, location);
        if (resource != null) {
            return resource;
        }
        String path = location.getPath();
        if (type == PackType.SERVER_DATA) {
            return laterDataPath(path)
                    .map(later -> pack.getResource(type, location.withPath(later)))
                    .orElse(null);
        }
        if (path.startsWith(ITEM_MODELS)) {
            IoSupplier<InputStream> definition =
                    pack.getResource(type, location.withPath(ITEM_DEFINITIONS + path.substring(ITEM_MODELS.length())));
            return definition == null ? null : itemModel(location, definition);
        }
        return null;
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        Set<ResourceLocation> seen = new HashSet<>();
        pack.listResources(type, namespace, path, (location, resource) -> {
            seen.add(location);
            output.accept(location, resource);
        });
        if (type == PackType.SERVER_DATA) {
            laterDataPath(path)
                    .ifPresent(later -> pack.listResources(type, namespace, later, (location, resource) -> {
                        ResourceLocation older =
                                location.withPath(path + location.getPath().substring(later.length()));
                        if (seen.add(older)) {
                            output.accept(older, resource);
                        }
                    }));
        } else if (path.equals("models") || path.equals("models/item")) {
            pack.listResources(type, namespace, "items", (location, definition) -> {
                if (location.getPath().endsWith(".json")) {
                    ResourceLocation model =
                            location.withPath(ITEM_MODELS + location.getPath().substring(ITEM_DEFINITIONS.length()));
                    if (seen.add(model)) {
                        output.accept(model, itemModel(model, definition));
                    }
                }
            });
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return pack.getNamespaces(type);
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        return pack.getMetadataSection(serializer);
    }

    @Override
    public String packId() {
        return pack.packId();
    }

    /**
     * Always: a mod's data is installed content. {@code RegistryDataLoader} registers an entry of a pack that is not built
     * in as experimental, which would make every world that loads a mod's world generation data ask for a backup.
     */
    @Override
    public boolean isBuiltin() {
        return true;
    }

    @Override
    public void close() {
        pack.close();
    }
}
