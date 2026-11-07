package dev.plexusmodloader.compat.fabric.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Mixin names a Fabric mod wrote in the names it was developed with ({@code method = "tryBreakBlock"}, an {@code @At}
 * {@code target} of a yarn method) that the mod's reference map ({@code "refmap"} of its Mixin configuration) turns into
 * the names of the game it runs on. Mixin does that lookup itself, when it applies the mixin; {@link
 * HostPatchAdjustments} compares the annotations with the game's classes before that, so without the lookup it saw yarn
 * names that no game class has and left every rule that works on a call or a field ({@link
 * dev.plexusmodloader.compat.bridge.InjectionPointReplacement.Placement#SAME_PLACE}, {@code IN_METHOD}) unapplied. Only
 * rules by handler name ({@link HostPatchAdjustments#HOST_COVERS}) worked.
 *
 * <p>For a mod on Minecraft before 26.1 the reference map is the one {@code ModJarTranslator} already rewrote to Mojang's
 * names (ADR-0037), so the answer is the name of the game here. The lookup is Mixin's own: the {@code mappings} of the
 * reference map, then its {@code data} for {@code named:intermediary}, by the mixin class and the string as written; a
 * string the map does not know stays as it is. {@code remap = false} (on the mixin, the injector or the {@code @At}) means
 * no lookup, and none is made.
 *
 * <p>The resolved strings are written into the mixin class both when the mixin is checked and when it is loaded ({@link
 * #apply}), so the rules of {@link HostPatchAdjustments} compare with, and rewrite, the same strings; Mixin finds none of
 * them in the reference map and uses them as they are. A mod without a reference map, or a mixin without an entry in it,
 * is not touched.
 *
 * <p>Thread: confined to {@link HostPatchAdjustments#plan} while planning; {@link #apply} is read-only afterwards.
 */
final class HostRefmapNames {

    static final HostRefmapNames NONE = new HostRefmapNames(Map.of());

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    /** The elements of Mixin's and MixinExtras' annotations that hold a name Mixin looks up in the reference map. */
    private static final Set<String> LOOKED_UP = Set.of("method", "target");

    /** Per mixin (internal name): each string as the mod wrote it to the name of the game here. */
    private final Map<String, Map<String, String>> names;

    private HostRefmapNames(Map<String, Map<String, String>> names) {
        this.names = names;
    }

    /** Collects the names while {@link HostPatchAdjustments#plan} goes through the mixins. */
    static final class Planner {

        private final Map<String, Map<String, String>> names = new HashMap<>();

        /** {@code bytes} of mixin {@code mixin} with the reference map's names; remembers them for {@link #apply}. */
        byte[] resolve(String mixin, byte[] bytes, Map<String, Map<String, String>> referenceMap) {
            Map<String, String> own = referenceMap.get(mixin);
            if (own == null || own.isEmpty()) {
                return bytes;
            }
            byte[] resolved = HostRefmapNames.resolve(bytes, own);
            if (resolved != bytes) {
                names.put(mixin, own);
            }
            return resolved;
        }

        HostRefmapNames build() {
            return names.isEmpty() ? NONE : new HostRefmapNames(Map.copyOf(names));
        }
    }

    /** {@code bytes} of mixin {@code mixin} (internal name) as {@link Planner#resolve} read it. */
    byte[] apply(String mixin, byte[] bytes) {
        Map<String, String> own = names.get(mixin);
        return own == null ? bytes : resolve(bytes, own);
    }

    /**
     * The reference map that Mixin configuration {@code config} of {@code file} names ({@code "refmap"}): the mixin
     * class (internal name) to its strings and their names. Empty without a configuration, a reference map or a readable
     * one.
     */
    static Map<String, Map<String, String>> read(JarFile file, String config) throws IOException {
        JsonObject configuration = object(file, config);
        if (configuration == null
                || !(configuration.get("refmap") instanceof JsonElement refmap)
                || !refmap.isJsonPrimitive()) {
            return Map.of();
        }
        JsonObject root = object(file, refmap.getAsString());
        if (root == null) {
            return Map.of();
        }
        Map<String, Map<String, String>> result = new HashMap<>();
        // Mixin looks in "mappings" when the environment has no obfuscation context (Plexus sets none), and in "data"
        // for a context. Fabric's tools write the same names to both.
        collect(root.get("mappings"), result);
        if (root.get("data") instanceof JsonObject data) {
            collect(data.get("named:intermediary"), result);
        }
        return result;
    }

    private static void collect(JsonElement section, Map<String, Map<String, String>> result) {
        if (!(section instanceof JsonObject classes)) {
            return;
        }
        for (Map.Entry<String, JsonElement> mixin : classes.entrySet()) {
            if (!(mixin.getValue() instanceof JsonObject strings)) {
                continue;
            }
            Map<String, String> own = result.computeIfAbsent(mixin.getKey(), key -> new HashMap<>());
            for (Map.Entry<String, JsonElement> string : strings.entrySet()) {
                if (string.getValue().isJsonPrimitive()
                        && string.getValue().getAsJsonPrimitive().isString()) {
                    own.putIfAbsent(string.getKey(), string.getValue().getAsString());
                }
            }
        }
    }

    private static JsonObject object(JarFile file, String path) throws IOException {
        ZipEntry entry = file.getEntry(path);
        if (entry == null) {
            return null;
        }
        try (InputStream input = file.getInputStream(entry)) {
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            instanceof JsonObject object
                    ? object
                    : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    /**
     * {@code bytes} with the {@code method} and {@code target} strings of its injectors (and their nested {@code @At} and
     * {@code @Slice}) replaced where {@code names} knows them; {@code bytes} itself (the same array) when none is.
     */
    static byte[] resolve(byte[] bytes, Map<String, String> names) {
        ClassFile classFile = ClassFile.of();
        ClassModel model = classFile.parse(bytes);
        if (!remapped(model)) {
            return bytes;
        }
        boolean[] changed = {false};
        byte[] result = classFile.transformClass(model, (builder, element) -> {
            if (element instanceof MethodModel method) {
                builder.transformMethod(method, (methodBuilder, methodElement) -> {
                    if (methodElement instanceof RuntimeVisibleAnnotationsAttribute visible) {
                        methodBuilder.with(
                                RuntimeVisibleAnnotationsAttribute.of(resolved(visible.annotations(), names, changed)));
                    } else if (methodElement instanceof RuntimeInvisibleAnnotationsAttribute invisible) {
                        methodBuilder.with(RuntimeInvisibleAnnotationsAttribute.of(
                                resolved(invisible.annotations(), names, changed)));
                    } else {
                        methodBuilder.with(methodElement);
                    }
                });
            } else {
                builder.with(element);
            }
        });
        return changed[0] ? result : bytes;
    }

    /** Whether the mixin looks names up in the reference map: {@code @Mixin(remap = false)} says it does not. */
    private static boolean remapped(ClassModel model) {
        return java.util.stream.Stream.concat(
                        model.findAttribute(java.lang.classfile.Attributes.runtimeInvisibleAnnotations()).stream()
                                .flatMap(attribute -> attribute.annotations().stream()),
                        model.findAttribute(java.lang.classfile.Attributes.runtimeVisibleAnnotations()).stream()
                                .flatMap(attribute -> attribute.annotations().stream()))
                .filter(annotation -> annotation.className().equalsString(MIXIN))
                .noneMatch(HostRefmapNames::notRemapped);
    }

    private static boolean notRemapped(Annotation annotation) {
        return annotation.elements().stream()
                .anyMatch(element -> element.name().equalsString("remap")
                        && element.value() instanceof AnnotationValue.OfBoolean flag
                        && !flag.booleanValue());
    }

    private static List<Annotation> resolved(
            List<Annotation> annotations, Map<String, String> names, boolean[] changed) {
        List<Annotation> result = new ArrayList<>(annotations.size());
        for (Annotation annotation : annotations) {
            result.add(resolved(annotation, names, changed));
        }
        return result;
    }

    private static Annotation resolved(Annotation annotation, Map<String, String> names, boolean[] changed) {
        if (notRemapped(annotation)) {
            return annotation;
        }
        List<AnnotationElement> elements = new ArrayList<>(annotation.elements().size());
        for (AnnotationElement element : annotation.elements()) {
            elements.add(AnnotationElement.of(
                    element.name().stringValue(),
                    resolved(element.value(), LOOKED_UP.contains(element.name().stringValue()), names, changed)));
        }
        return Annotation.of(annotation.classSymbol(), elements);
    }

    private static AnnotationValue resolved(
            AnnotationValue value, boolean lookedUp, Map<String, String> names, boolean[] changed) {
        return switch (value) {
            case AnnotationValue.OfString text
            when lookedUp -> {
                String name = names.get(text.stringValue());
                if (name == null || name.equals(text.stringValue())) {
                    yield value;
                }
                changed[0] = true;
                yield AnnotationValue.ofString(name);
            }
            case AnnotationValue.OfArray array ->
                AnnotationValue.ofArray(array.values().stream()
                        .map(item -> resolved(item, lookedUp, names, changed))
                        .toList());
            case AnnotationValue.OfAnnotation nested ->
                AnnotationValue.ofAnnotation(resolved(nested.annotation(), names, changed));
            default -> value;
        };
    }
}
