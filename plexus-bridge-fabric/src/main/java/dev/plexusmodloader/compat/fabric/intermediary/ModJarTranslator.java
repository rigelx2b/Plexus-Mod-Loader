package dev.plexusmodloader.compat.fabric.intermediary;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.plexusmodloader.compat.fabric.mixin.MixinConfigEntry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Writes a copy of a Fabric-family mod jar that names the game in Mojang's names instead of Fabric's intermediary
 * names (ADR-0037): every class file is remapped ({@link IntermediaryRemapper}), the Mixin reference maps the mod's
 * Mixin configurations name are rewritten, and class tweaker files in the {@code intermediary} namespace are rewritten
 * to {@code official} ({@link ClassTweakerTranslator}). Everything else, nested jars included, is copied as it is.
 *
 * <p>The input is never changed. When nothing in the jar names the game by intermediary names, no copy is written and
 * {@link Result#changed()} is {@code false}. Signature files are left out of a copy: the signatures cover the bytes
 * that were changed.
 *
 * <p>Thread: stateless; safe from any thread, one call per jar.
 */
final class ModJarTranslator {

    /**
     * What a translation did.
     *
     * @param changed whether a copy was written; {@code false} when the jar holds no intermediary names
     * @param classes class files whose bytes changed
     * @param resources reference maps and class tweakers that changed
     * @param unreadable class files ASM could not read (kept as they are)
     */
    record Result(boolean changed, int classes, int resources, int unreadable) {}

    private static final long FIXED_TIME = 315532800000L; // 1980-01-01, the earliest time a zip entry holds

    private ModJarTranslator() {}

    /**
     * Translates {@code input} into {@code output} (replaced if it exists). Nothing is left at {@code output} when the
     * jar needs no translation.
     */
    static Result translate(IntermediaryTranslation translation, Path input, Path output) throws IOException {
        Path partial = output.resolveSibling(output.getFileName() + ".partial");
        int classes = 0;
        int resources = 0;
        int unreadable = 0;
        try (ZipFile zip = new ZipFile(input.toFile());
                OutputStream file = Files.newOutputStream(partial);
                ZipOutputStream out = new ZipOutputStream(file)) {
            out.setLevel(Deflater.BEST_SPEED);
            Set<String> refmaps = referenceMaps(zip);
            Map<String, Set<String>> referenceKeys = referenceKeys(zip, refmaps);
            Set<String> tweakers = classTweakers(zip);
            Set<String> written = new LinkedHashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (isSignature(name)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    if (written.add(name)) {
                        put(out, name, new byte[0]);
                    }
                    continue;
                }
                byte[] bytes = read(zip, entry);
                if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                    ClassResult remapped = remapClass(translation, bytes, referenceKeys);
                    if (remapped == null) {
                        unreadable++;
                    } else if (remapped.bytes() != bytes) {
                        classes++;
                        bytes = remapped.bytes();
                        name = remapped.name() + ".class";
                    }
                } else if (refmaps.contains(name)) {
                    Optional<byte[]> mapped = referenceMap(translation, bytes);
                    if (mapped.isPresent()) {
                        resources++;
                        bytes = mapped.get();
                    }
                } else if (tweakers.contains(name)) {
                    Optional<String> mapped =
                            ClassTweakerTranslator.translate(new String(bytes, StandardCharsets.UTF_8), translation);
                    if (mapped.isPresent()) {
                        resources++;
                        bytes = mapped.get().getBytes(StandardCharsets.UTF_8);
                    }
                }
                if (written.add(name)) {
                    put(out, name, bytes);
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        if (classes == 0 && resources == 0) {
            Files.deleteIfExists(partial);
            Files.deleteIfExists(output);
            return new Result(false, 0, 0, unreadable);
        }
        Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
        return new Result(true, classes, resources, unreadable);
    }

    private record ClassResult(String name, byte[] bytes) {}

    /** The remapped class, or {@code null} when ASM cannot read it (a newer class file version than it knows). */
    private static ClassResult remapClass(
            IntermediaryTranslation translation, byte[] bytes, Map<String, Set<String>> referenceKeys) {
        ClassReader reader;
        try {
            reader = new ClassReader(bytes);
        } catch (IllegalArgumentException e) {
            return null;
        }
        IntermediaryRemapper remapper = new IntermediaryRemapper(translation);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(
                new SelectorAwareClassRemapper(
                        writer, remapper, translation, referenceKeys.getOrDefault(reader.getClassName(), Set.of())),
                0);
        if (!remapper.changed()) {
            return new ClassResult(reader.getClassName(), bytes);
        }
        return new ClassResult(translation.mapClass(reader.getClassName()), writer.toByteArray());
    }

    private static Optional<byte[]> referenceMap(IntermediaryTranslation translation, byte[] bytes) {
        JsonElement root;
        try {
            root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        boolean[] changed = {false};
        JsonElement mapped = mapStrings(root, translation, changed);
        return changed[0] ? Optional.of(mapped.toString().getBytes(StandardCharsets.UTF_8)) : Optional.empty();
    }

    /** Every string value (not key) of a reference map, translated. */
    private static JsonElement mapStrings(JsonElement element, IntermediaryTranslation translation, boolean[] changed) {
        if (element.isJsonObject()) {
            JsonObject copy = new JsonObject();
            for (Map.Entry<String, JsonElement> entry :
                    element.getAsJsonObject().entrySet()) {
                copy.add(entry.getKey(), mapStrings(entry.getValue(), translation, changed));
            }
            return copy;
        }
        if (element.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                copy.add(mapStrings(item, translation, changed));
            }
            return copy;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String text = element.getAsString();
            String mapped = translation.mapText(text);
            if (!mapped.equals(text)) {
                changed[0] = true;
                return new JsonPrimitive(mapped);
            }
        }
        return element;
    }

    /**
     * The keys of each Mixin class in the jar's reference maps ({@code mappings} and every {@code data} context): the
     * strings Mixin looks up there, which the class's annotations keep as written ({@link SelectorAwareClassRemapper}).
     */
    private static Map<String, Set<String>> referenceKeys(ZipFile zip, Set<String> refmaps) {
        Map<String, Set<String>> keys = new HashMap<>();
        for (String refmap : refmaps) {
            ZipEntry entry = zip.getEntry(refmap);
            JsonElement root = entry == null ? null : parse(zip, entry);
            if (root == null || !root.isJsonObject()) {
                continue;
            }
            List<JsonObject> tables = new ArrayList<>();
            if (root.getAsJsonObject().get("mappings") instanceof JsonObject mappings) {
                tables.add(mappings);
            }
            if (root.getAsJsonObject().get("data") instanceof JsonObject data) {
                for (Map.Entry<String, JsonElement> context : data.entrySet()) {
                    if (context.getValue() instanceof JsonObject table) {
                        tables.add(table);
                    }
                }
            }
            for (JsonObject table : tables) {
                for (Map.Entry<String, JsonElement> mixin : table.entrySet()) {
                    if (mixin.getValue() instanceof JsonObject entries) {
                        keys.computeIfAbsent(mixin.getKey(), any -> new HashSet<>())
                                .addAll(entries.keySet());
                    }
                }
            }
        }
        return keys;
    }

    /** The reference maps the jar's Mixin configurations name. */
    private static Set<String> referenceMaps(ZipFile zip) {
        Set<String> maps = new LinkedHashSet<>();
        for (String config : mixinConfigs(zip)) {
            ZipEntry entry = zip.getEntry(config);
            if (entry == null) {
                continue;
            }
            JsonElement root = parse(zip, entry);
            if (root != null && root.isJsonObject() && root.getAsJsonObject().has("refmap")) {
                maps.add(root.getAsJsonObject().get("refmap").getAsString());
            }
        }
        return maps;
    }

    /** Every configuration either metadata names, for both sides or one, read as the bridges read them. */
    private static Set<String> mixinConfigs(ZipFile zip) {
        Set<String> configs = new LinkedHashSet<>();
        JsonObject fabric = metadata(zip, "fabric.mod.json");
        if (fabric != null) {
            MixinConfigEntry.parse(fabric.get("mixins")).entries().forEach(entry -> configs.add(entry.config()));
        }
        JsonObject quilt = metadata(zip, "quilt.mod.json");
        if (quilt != null) {
            MixinConfigEntry.parseQuilt(quilt.get("mixin")).entries().forEach(entry -> configs.add(entry.config()));
        }
        return configs;
    }

    /** The class tweaker files the metadata names, and any file with a class tweaker extension. */
    private static Set<String> classTweakers(ZipFile zip) {
        Set<String> tweakers = new LinkedHashSet<>();
        JsonObject fabric = metadata(zip, "fabric.mod.json");
        if (fabric != null
                && fabric.has("accessWidener")
                && fabric.get("accessWidener").isJsonPrimitive()) {
            tweakers.add(fabric.get("accessWidener").getAsString());
        }
        JsonObject quilt = metadata(zip, "quilt.mod.json");
        if (quilt != null && quilt.has("access_widener")) {
            JsonElement named = quilt.get("access_widener");
            if (named.isJsonPrimitive()) {
                tweakers.add(named.getAsString());
            } else if (named.isJsonArray()) {
                for (JsonElement item : named.getAsJsonArray()) {
                    if (item.isJsonPrimitive()) {
                        tweakers.add(item.getAsString());
                    }
                }
            }
        }
        for (ZipEntry entry : Collections.list(zip.entries())) {
            String lower = entry.getName().toLowerCase(Locale.ROOT);
            if (!entry.isDirectory() && (lower.endsWith(".accesswidener") || lower.endsWith(".classtweaker"))) {
                tweakers.add(entry.getName());
            }
        }
        return tweakers;
    }

    private static JsonObject metadata(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        JsonElement root = parse(zip, entry);
        return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
    }

    private static JsonElement parse(ZipFile zip, ZipEntry entry) {
        try {
            return JsonParser.parseString(new String(read(zip, entry), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isSignature(String name) {
        if (!name.startsWith("META-INF/") || name.indexOf('/', "META-INF/".length()) >= 0) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_TIME);
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    /** Names of the entries of a jar, for tests. */
    static List<String> entryNames(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<String> names = new ArrayList<>();
            zip.stream().forEach(entry -> names.add(entry.getName()));
            return names;
        }
    }
}
