package dev.plexusmodloader.compat.fabric.intermediary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mojang's official mappings of an obfuscated Minecraft version, in the ProGuard format of {@code
 * downloads.client_mappings} and {@code downloads.server_mappings} (ADR-0019): the Mojang name of each class, field
 * and method and the obfuscated name the original jar uses. The installer and the build keep this file next to the
 * game jar they remapped with it; this class reads it so that intermediary names can be translated to the names that
 * jar has (ADR-0037).
 *
 * <p>The same grammar as the installer's and the build's readers, which this bridge cannot use. Lines for inlined
 * frames (a method name with a dot) are skipped.
 *
 * <p>Thread: immutable after {@link #read}.
 */
final class MojangNames {

    private static final Pattern CLASS_LINE = Pattern.compile("^(\\S+) -> (\\S+):$");
    private static final Pattern METHOD_LINE =
            Pattern.compile("^\\s+(?:\\d+:\\d+:)?(\\S+) ([^\\s(]+)\\(([^)]*)\\)(?::\\d+(?::\\d+)?)? -> (\\S+)$");
    private static final Pattern FIELD_LINE = Pattern.compile("^\\s+(\\S+) ([^\\s(]+) -> (\\S+)$");

    /** Obfuscated internal class name to the Mojang one. */
    private final Map<String, String> classNames = new HashMap<>();
    /** Per obfuscated class: obfuscated {@code name + descriptor} to the Mojang method name. */
    private final Map<String, Map<String, String>> methodNames = new HashMap<>();
    /** Per obfuscated class: obfuscated field name to the Mojang field name. */
    private final Map<String, Map<String, String>> fieldNames = new HashMap<>();

    private MojangNames() {}

    static MojangNames read(Path file) {
        try {
            return parse(Files.readAllLines(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the Mojang mappings " + file, e);
        }
    }

    static MojangNames parse(List<String> lines) {
        // Members name their types with Mojang names, so classes are read first and members second.
        Map<String, String> obfuscatedOf = new HashMap<>();
        for (String line : lines) {
            Matcher type = CLASS_LINE.matcher(line);
            if (!line.startsWith("#") && type.matches()) {
                obfuscatedOf.put(internal(type.group(1)), internal(type.group(2)));
            }
        }
        MojangNames names = new MojangNames();
        obfuscatedOf.forEach((named, obfuscated) -> names.classNames.put(obfuscated, named));
        String owner = null;
        for (String line : lines) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher type = CLASS_LINE.matcher(line);
            if (type.matches()) {
                owner = internal(type.group(2));
                continue;
            }
            if (owner == null) {
                continue;
            }
            Matcher method = METHOD_LINE.matcher(line);
            if (method.matches()) {
                if (method.group(2).contains(".")) {
                    continue;
                }
                String descriptor = "(" + parameters(method.group(3), obfuscatedOf) + ")"
                        + descriptor(method.group(1), obfuscatedOf);
                names.methodNames
                        .computeIfAbsent(owner, ignored -> new HashMap<>())
                        .putIfAbsent(method.group(4) + descriptor, method.group(2));
                continue;
            }
            Matcher field = FIELD_LINE.matcher(line);
            if (field.matches()) {
                names.fieldNames
                        .computeIfAbsent(owner, ignored -> new HashMap<>())
                        .putIfAbsent(field.group(3), field.group(2));
            }
        }
        return names;
    }

    /** The Mojang name of an obfuscated internal class name, or the argument if the mappings do not list it. */
    String className(String obfuscated) {
        return classNames.getOrDefault(obfuscated, obfuscated);
    }

    /** The Mojang name of a method, or the obfuscated one if the mappings do not list it. */
    String methodName(String obfuscatedOwner, String name, String obfuscatedDescriptor) {
        Map<String, String> methods = methodNames.get(obfuscatedOwner);
        String mojang = methods == null ? null : methods.get(name + obfuscatedDescriptor);
        return mojang == null ? name : mojang;
    }

    /** The Mojang name of a field, or the obfuscated one if the mappings do not list it. */
    String fieldName(String obfuscatedOwner, String name) {
        Map<String, String> fields = fieldNames.get(obfuscatedOwner);
        String mojang = fields == null ? null : fields.get(name);
        return mojang == null ? name : mojang;
    }

    int classCount() {
        return classNames.size();
    }

    private static String internal(String javaName) {
        return javaName.replace('.', '/');
    }

    private static String parameters(String list, Map<String, String> obfuscatedOf) {
        if (list.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String type : list.split(",")) {
            out.append(descriptor(type, obfuscatedOf));
        }
        return out.toString();
    }

    /** The obfuscated descriptor of a Java type written with Mojang names, such as {@code net.minecraft.A[]}. */
    private static String descriptor(String javaType, Map<String, String> obfuscatedOf) {
        String type = javaType;
        StringBuilder arrays = new StringBuilder();
        while (type.endsWith("[]")) {
            arrays.append('[');
            type = type.substring(0, type.length() - 2);
        }
        String element = switch (type) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> "L" + obfuscatedOf.getOrDefault(internal(type), internal(type)) + ";";
        };
        return arrays + element;
    }
}
