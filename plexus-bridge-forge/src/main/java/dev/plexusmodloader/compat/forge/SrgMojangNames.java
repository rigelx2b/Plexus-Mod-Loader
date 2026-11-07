package dev.plexusmodloader.compat.forge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names of Minecraft's fields and methods in MinecraftForge 1.20.x's runtime (SRG: {@code f_123_}, {@code m_123_})
 * and the names Plexus Mod Loader's game layer, transformation rules and the Fabric bridge use (Mojang's, ADR-0019),
 * for one side. ADR-0038 point 5: Forge 1.20.1 and 1.20.4 run Mojang's class names with SRG members, so Plexus Mod Loader
 * renames every SRG member it finds in Forge's and the mods' files to the Mojang name before FML sees them.
 *
 * <p>An SRG id belongs to one method or field of one class hierarchy and never to another, so the name alone identifies
 * the member and the translation does not need the owner. The table is made from two files Forge's own installer tools
 * write while they rename the game jar ({@link ForgeInstallerTools}): {@code MERGED_MAPPINGS} ({@code tsrg2}: obfuscated
 * name to Mojang class name, member by obfuscated name and descriptor to SRG name) and {@code MOJMAPS} (Mojang's own
 * mappings: Mojang class name to obfuscated name, member by Mojang name to obfuscated name). The two are joined by the
 * obfuscated member name. The table stays on this machine and is never redistributed.
 *
 * <p>Immutable after construction; safe for concurrent reads.
 */
final class SrgMojangNames {

    /** An SRG field or method name; the id is unique in the game. */
    static final Pattern SRG = Pattern.compile("(?<![A-Za-z0-9_$])[mf]_\\d+_(?![A-Za-z0-9_$])");

    private static final Pattern EXACT = Pattern.compile("[mf]_\\d+_");

    private final Map<String, String> names;

    private SrgMojangNames(Map<String, String> names) {
        this.names = Map.copyOf(names);
    }

    /** A table of the given SRG name to Mojang name pairs. */
    static SrgMojangNames of(Map<String, String> names) {
        return new SrgMojangNames(names);
    }

    /** How many SRG names the table translates. */
    int size() {
        return names.size();
    }

    /** The Mojang name for an SRG name, or {@code name} itself when it is not an SRG name or has no Mojang name. */
    String translate(String name) {
        return names.getOrDefault(name, name);
    }

    /** Whether {@code text} is exactly one SRG name (not just contains one). */
    static boolean isSrgName(String text) {
        return text.length() > 3 && EXACT.matcher(text).matches();
    }

    /** {@code text} with every SRG name in it replaced by its Mojang name (access transformers, refmaps, annotations). */
    String translateText(String text) {
        Matcher matcher = SRG.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        do {
            out.append(text, last, matcher.start()).append(translate(matcher.group()));
            last = matcher.end();
        } while (matcher.find());
        return out.append(text, last, text.length()).toString();
    }

    /**
     * Makes the table from the two files of Forge's installer tools.
     *
     * @param mergedMappings {@code MERGED_MAPPINGS}: {@code tsrg2 left right}, obfuscated to Mojang class and SRG member
     * @param mojangMappings {@code MOJMAPS}: Mojang class to obfuscated class, Mojang member to obfuscated member
     * @throws IOException when a file cannot be read or is not in the expected form
     */
    static SrgMojangNames join(Path mergedMappings, Path mojangMappings) throws IOException {
        List<String> merged = Files.readAllLines(mergedMappings, StandardCharsets.UTF_8);
        if (merged.isEmpty() || !merged.getFirst().startsWith("tsrg2 ")) {
            throw new IOException(mergedMappings.getFileName() + " is not a tsrg2 file");
        }
        List<String> mojang = Files.readAllLines(mojangMappings, StandardCharsets.UTF_8);

        // obfuscated class -> Mojang class, to write obfuscated descriptors with Mojang class names.
        Map<String, String> classes = new HashMap<>();
        for (String line : merged) {
            if (!line.isEmpty() && line.charAt(0) != '\t' && !line.startsWith("tsrg2 ")) {
                String[] parts = line.split(" ");
                if (parts.length >= 2) {
                    classes.put(parts[0], parts[1]);
                }
            }
        }

        // (Mojang class, obfuscated member name [, Mojang descriptor]) -> Mojang member name.
        Map<String, String> byObfuscated = isProguard(mojang) ? readProguard(mojang) : readTsrg(mojang);

        Map<String, String> result = new TreeMap<>();
        String currentClass = null;
        for (String line : merged) {
            if (line.isEmpty() || line.startsWith("tsrg2 ")) {
                continue;
            }
            if (line.charAt(0) != '\t') {
                currentClass = line.split(" ")[1];
            } else if (line.charAt(1) != '\t' && currentClass != null) {
                String[] parts = line.substring(1).split(" ");
                String key;
                String srg;
                if (parts.length == 2) {
                    key = currentClass + " " + parts[0];
                    srg = parts[1];
                } else if (parts.length == 3) {
                    key = currentClass + " " + parts[0] + " " + describe(parts[1], classes);
                    srg = parts[2];
                } else {
                    continue;
                }
                String name = byObfuscated.get(key);
                if (name != null && isSrgName(srg) && !name.equals(srg)) {
                    result.putIfAbsent(srg, name);
                }
            }
        }
        return new SrgMojangNames(result);
    }

    /** Mojang's own file as Forge 1.20.1's {@code DOWNLOAD_MOJMAPS} leaves it (no {@code --sanitize}): ProGuard form. */
    private static boolean isProguard(List<String> lines) {
        for (String line : lines) {
            if (!line.isEmpty() && line.charAt(0) != '#') {
                return line.contains(" -> ");
            }
        }
        return false;
    }

    /** The sanitised form ({@code --sanitize}, Forge 1.20.4 and later): tsrg, Mojang names to obfuscated names. */
    private static Map<String, String> readTsrg(List<String> lines) {
        Map<String, String> byObfuscated = new HashMap<>();
        String currentClass = null;
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) != '\t') {
                currentClass = line.split(" ")[0];
            } else if (line.charAt(1) != '\t' && currentClass != null) {
                String[] parts = line.substring(1).split(" ");
                if (parts.length == 2) {
                    byObfuscated.put(currentClass + " " + parts[1], parts[0]);
                } else if (parts.length == 3) {
                    byObfuscated.put(currentClass + " " + parts[2] + " " + parts[1], parts[0]);
                }
            }
        }
        return byObfuscated;
    }

    /**
     * ProGuard form: {@code com.mojang.math.Axis -> a:}, then indented {@code type name -> obf} (field) and {@code
     * [from:to:]type name(argument types) -> obf} (method) lines, with Java type names.
     */
    private static Map<String, String> readProguard(List<String> lines) {
        Map<String, String> byObfuscated = new HashMap<>();
        String currentClass = null;
        for (String line : lines) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int arrow = line.indexOf(" -> ");
            if (arrow < 0) {
                continue;
            }
            if (line.charAt(0) != ' ') {
                currentClass = line.substring(0, arrow).replace('.', '/');
                continue;
            }
            if (currentClass == null) {
                continue;
            }
            String obfuscated = line.substring(arrow + 4).strip();
            String member = line.substring(0, arrow).strip();
            // Drop the line numbers of a method: 17:17:type name(args)
            int colon = member.lastIndexOf(':');
            if (colon >= 0) {
                member = member.substring(colon + 1);
            }
            int space = member.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String type = member.substring(0, space);
            String rest = member.substring(space + 1);
            int open = rest.indexOf('(');
            if (open < 0) {
                byObfuscated.put(currentClass + " " + obfuscated, rest);
            } else {
                String arguments = rest.substring(open + 1, rest.length() - 1);
                StringBuilder descriptor = new StringBuilder("(");
                if (!arguments.isEmpty()) {
                    for (String argument : arguments.split(",")) {
                        descriptor.append(descriptorOf(argument.strip()));
                    }
                }
                descriptor.append(')').append(descriptorOf(type));
                byObfuscated.put(currentClass + " " + obfuscated + " " + descriptor, rest.substring(0, open));
            }
        }
        return byObfuscated;
    }

    /** A Java type name of the ProGuard form as a descriptor: {@code int[]} to {@code [I}. */
    private static String descriptorOf(String type) {
        int dimensions = 0;
        String base = type;
        while (base.endsWith("[]")) {
            dimensions++;
            base = base.substring(0, base.length() - 2);
        }
        String element = switch (base) {
            case "byte" -> "B";
            case "char" -> "C";
            case "double" -> "D";
            case "float" -> "F";
            case "int" -> "I";
            case "long" -> "J";
            case "short" -> "S";
            case "boolean" -> "Z";
            case "void" -> "V";
            default -> "L" + base.replace('.', '/') + ";";
        };
        return "[".repeat(dimensions) + element;
    }

    /** {@code descriptor} with each class name replaced through {@code classes}. */
    private static String describe(String descriptor, Map<String, String> classes) {
        StringBuilder out = new StringBuilder(descriptor.length() + 16);
        int i = 0;
        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                String name = descriptor.substring(i + 1, end);
                out.append('L').append(classes.getOrDefault(name, name)).append(';');
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Reads a table written by {@link #write(Path)}. */
    static SrgMojangNames read(Path file) throws IOException {
        Map<String, String> names = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int space = line.indexOf(' ');
            if (space > 0) {
                names.put(line.substring(0, space), line.substring(space + 1));
            }
        }
        return new SrgMojangNames(names);
    }

    /** Writes one {@code srg mojang} pair per line, sorted. */
    void write(Path file) throws IOException {
        List<String> lines = new ArrayList<>(names.size());
        new TreeMap<>(names).forEach((srg, mojang) -> lines.add(srg + " " + mojang));
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        Files.write(partial, lines, StandardCharsets.UTF_8);
        Files.move(partial, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
