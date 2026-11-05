package dev.plexusmodloader.game.layer.v1_21_1;

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
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * A mod's pack as Minecraft 1.21.1 reads it. One mod jar runs on every version it lists, and the SDK's
 * {@code plexusResources} writes the layout of later versions: 1.21.1 finds an item's model at
 * {@code models/item/<name>.json} because it has no item definitions ({@code items/<name>.json}, which 1.21.4 added).
 * This pack answers that name from the definition; a model file under that name always wins.
 */
final class LaterFormatPack implements PackResources {

    private static final String ITEM_MODELS = "models/item/";
    private static final String ITEM_DEFINITIONS = "items/";

    private final PackResources pack;

    LaterFormatPack(PackResources pack) {
        this.pack = pack;
    }

    /**
     * The model an item definition shows: its {@code minecraft:model}, or for one that chooses between models (by
     * property, condition or range) the first it names, since 1.21.1's item model is a single one.
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
        if (type == PackType.CLIENT_RESOURCES && path.startsWith(ITEM_MODELS)) {
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
        if (type == PackType.CLIENT_RESOURCES && (path.equals("models") || path.equals("models/item"))) {
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
    public PackLocationInfo location() {
        return pack.location();
    }

    @Override
    public void close() {
        pack.close();
    }
}
