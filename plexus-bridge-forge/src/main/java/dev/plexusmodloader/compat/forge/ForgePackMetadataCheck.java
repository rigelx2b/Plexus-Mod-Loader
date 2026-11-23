package dev.plexusmodloader.compat.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Forge on NeoForge (ADR-0051): a Forge mod's {@code pack.mcmeta} that stops the game on the NeoForge releases that read
 * mod pack metadata without a fallback. NeoForge's {@code ResourcePackLoader.readMeta} reads each mod file's metadata
 * with Minecraft's codec; in NeoForge 26.1.0.19-beta and 26.1.1.15-beta it catches nothing but the closing of the pack
 * ({@code Throwable} handlers of try-with-resources), so metadata that Minecraft rejects stops the game (NeoForge alone
 * too); from 26.1.2.122 on it catches Gson's {@code JsonParseException}, logs a warning and reads the pack another way
 * ({@code javap}, 2026-11-23). The rejection checked here is the one the corpus meets:
 * Minecraft 26.1 refuses a {@code min_format}/{@code max_format} range that overlaps data pack formats 17 to 81 without
 * a {@code supported_formats} field ("game versions supporting formats 17 to 81 require a supported_formats field";
 * UniLib 1.2.1 and CraftPresence 2.7.1 for 26.1 declare {@code [65, 0]} to {@code [84, 0]}). Other metadata Minecraft
 * rejects is not checked.
 *
 * <p>Thread: stateless.
 */
final class ForgePackMetadataCheck {

    static final String RESOURCE_PACK_LOADER = "net/neoforged/neoforge/resource/ResourcePackLoader.class";
    static final String READ_META = "readMeta";
    static final String PACK_METADATA = "pack.mcmeta";

    /** What NeoForge's fallback catches: Minecraft's codec error, as Gson's exception. */
    static final String JSON_PARSE_EXCEPTION = "com/google/gson/JsonParseException";

    /** Data pack formats that need {@code supported_formats} when a range declares them (Minecraft 26.1). */
    static final int FIRST_LISTED_FORMAT = 17;

    static final int LAST_LISTED_FORMAT = 81;

    private ForgePackMetadataCheck() {}

    /**
     * Whether NeoForge's {@code neoForgeUniversal} reads mod pack metadata without a fallback: its {@code
     * ResourcePackLoader.readMeta} catches no {@code JsonParseException} (or the jar does not have it as checked).
     *
     * @throws IOException when the jar or the class cannot be read
     */
    static boolean stopsOnRejectedMetadata(Path neoForgeUniversal) throws IOException {
        try (JarFile jar = new JarFile(neoForgeUniversal.toFile())) {
            JarEntry entry = jar.getJarEntry(RESOURCE_PACK_LOADER);
            if (entry == null) {
                return true;
            }
            ClassModel model;
            try (InputStream input = jar.getInputStream(entry)) {
                model = ClassFile.of().parse(input.readAllBytes());
            } catch (IllegalArgumentException e) {
                throw new IOException(RESOURCE_PACK_LOADER + " in " + neoForgeUniversal.getFileName() + ": " + e, e);
            }
            Optional<MethodModel> readMeta = model.methods().stream()
                    .filter(method -> method.methodName().equalsString(READ_META))
                    .findFirst();
            return readMeta.flatMap(MethodModel::code)
                    .map(code -> code.exceptionHandlers().stream()
                            .noneMatch(handler -> handler.catchType()
                                    .map(type -> type.asInternalName().equals(JSON_PARSE_EXCEPTION))
                                    .orElse(false)))
                    .orElse(true);
        }
    }

    /**
     * Why {@code jar}'s {@code pack.mcmeta} would stop a NeoForge release that reads it without a fallback; empty when
     * the jar has none, or Minecraft takes it as far as checked here.
     */
    static List<String> reasons(Path jar) {
        String text;
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(PACK_METADATA);
            if (entry == null) {
                return List.of();
            }
            try (InputStream input = file.getInputStream(entry)) {
                text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return List.of(PACK_METADATA + " cannot be read: " + e.getMessage());
        }
        JsonObject pack;
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("pack")) {
                return List.of();
            }
            pack = root.getAsJsonObject().getAsJsonObject("pack");
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            return List.of(PACK_METADATA + " is not a JSON object with a pack object: " + e.getMessage());
        }
        if (pack.has("supported_formats") || !pack.has("min_format")) {
            return List.of();
        }
        OptionalInt min = major(pack.get("min_format"));
        OptionalInt max = pack.has("max_format") ? major(pack.get("max_format")) : min;
        if (min.isEmpty() || max.isEmpty()) {
            return List.of();
        }
        if (min.getAsInt() > LAST_LISTED_FORMAT || max.getAsInt() < FIRST_LISTED_FORMAT) {
            return List.of();
        }
        return List.of(PACK_METADATA + " declares formats " + min.getAsInt() + " to " + max.getAsInt()
                + " without supported_formats, which Minecraft rejects for formats " + FIRST_LISTED_FORMAT + " to "
                + LAST_LISTED_FORMAT + ", and this NeoForge release stops the game on it");
    }

    /** The major format of a {@code min_format}/{@code max_format} value ({@code 65} or {@code [65, 0]}). */
    private static OptionalInt major(JsonElement value) {
        try {
            if (value.isJsonPrimitive()) {
                return OptionalInt.of(value.getAsInt());
            }
            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                return array.isEmpty()
                        ? OptionalInt.empty()
                        : OptionalInt.of(array.get(0).getAsInt());
            }
        } catch (NumberFormatException | IllegalStateException | UnsupportedOperationException e) {
            return OptionalInt.empty();
        }
        return OptionalInt.empty();
    }
}
