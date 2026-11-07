package dev.plexusmodloader.compat.fabric.intermediary;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * FabricMC's intermediary mappings of one Minecraft version (ADR-0037): the obfuscated name of every class, field and
 * method of the game and the stable name Fabric gave it ({@code net/minecraft/class_1937}, {@code method_8320}). Fabric
 * mods for Minecraft before 26.1 are built against those names. The file is Tiny v2 with the namespaces {@code
 * official} and {@code intermediary}, in {@code mappings/mappings.tiny} of the jar
 * {@code net.fabricmc:intermediary:<minecraft>:v2}.
 *
 * <p>Method and field descriptors in the file use the {@code official} (obfuscated) class names. Parameter, local
 * variable and comment lines are skipped.
 *
 * <p>Thread: instances are immutable after {@link #parse}.
 */
final class IntermediaryMappings {

    /** One field or method. {@code officialDescriptor} names classes by their obfuscated names. */
    record Member(String officialDescriptor, String officialName, String intermediaryName) {}

    /** One class with the members the file lists for it. */
    record ClassEntry(String official, String intermediary, List<Member> fields, List<Member> methods) {}

    private final List<ClassEntry> classes;

    private IntermediaryMappings(List<ClassEntry> classes) {
        this.classes = classes;
    }

    List<ClassEntry> classes() {
        return classes;
    }

    /** Reads {@code mappings/mappings.tiny} from the intermediary jar. */
    static IntermediaryMappings readJar(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("mappings/mappings.tiny");
            if (entry == null) {
                throw new IllegalArgumentException(jar + " has no mappings/mappings.tiny");
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return parse(input);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the intermediary mappings " + jar, e);
        }
    }

    /** Reads a Tiny v2 file that is already unpacked. */
    static IntermediaryMappings readFile(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            return parse(input);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the intermediary mappings " + file, e);
        }
    }

    static IntermediaryMappings parse(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String header = reader.readLine();
        if (header == null || !header.equals("tiny\t2\t0\tofficial\tintermediary")) {
            throw new IllegalArgumentException(
                    "The intermediary mappings must be Tiny v2 with the namespaces official and intermediary, but the"
                            + " first line is: " + header);
        }
        List<ClassEntry> classes = new ArrayList<>(8192);
        String official = null;
        String intermediary = null;
        List<Member> fields = null;
        List<Member> methods = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("c\t")) {
                if (official != null) {
                    classes.add(new ClassEntry(official, intermediary, fields, methods));
                }
                String[] parts = line.split("\t");
                official = parts[1];
                intermediary = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : official;
                fields = new ArrayList<>();
                methods = new ArrayList<>();
            } else if (official != null && (line.startsWith("\tf\t") || line.startsWith("\tm\t"))) {
                String[] parts = line.split("\t");
                if (parts.length < 5) {
                    continue;
                }
                Member member = new Member(parts[2], parts[3], parts[4]);
                (parts[1].equals("f") ? fields : methods).add(member);
            }
        }
        if (official != null) {
            classes.add(new ClassEntry(official, intermediary, fields, methods));
        }
        return new IntermediaryMappings(List.copyOf(classes));
    }
}
