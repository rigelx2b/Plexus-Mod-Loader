package dev.plexusmodloader.compat.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The step of NeoForge's installer that makes the patched game jar of an obfuscated Minecraft version (NeoForge 21.11
 * for Minecraft 1.21.11, ADR-0038): NeoForge's own {@code installertools} {@code PROCESS_MINECRAFT_JAR} renames the
 * obfuscated jar with Mojang's mappings and applies NeoForge's patches to the result. The patches check the bytes of
 * the renamed jar (Adler32), so only NeoForge's renaming produces a jar they apply to; Plexus Mod Loader's own remapped
 * jar ({@code ADR-0019}) differs in bytes. For the versions from 26.1 on, which are not obfuscated, {@link
 * NeoForgePatchBundle} applies the patches itself.
 *
 * <p>The tool is the {@code installertools} fat jar that NeoForge's {@code install_profile.json} lists (LGPL 2.1,
 * downloaded and checked against the SHA-1 there, never copied into Plexus Mod Loader), run in a separate JVM exactly as
 * NeoForge's installer runs it. Its output stays in the cache folder on this machine, like the remapped jars.
 */
final class NeoForgeInstallerTools {

    private static final String TASK = "PROCESS_MINECRAFT_JAR";
    private static final long TIMEOUT_MINUTES = 5;
    private static final int LOG_LINES = 12;

    private NeoForgeInstallerTools() {}

    /**
     * What the installer's processor needs, from {@code install_profile.json}.
     *
     * @param toolsLibrary the library name of the {@code installertools} fat jar
     * @param neoformData the library name of the NeoForm mappings the processor reads ({@code --neoform-data})
     * @param patches the entry of the installer with the binary patches for this side
     */
    record Plan(String toolsLibrary, String neoformData, String patches) {}

    /**
     * The processor that renames and patches the game jar, when the profile has one: a {@code PROCESS_MINECRAFT_JAR}
     * with {@code --input-mappings}. Versions that are not obfuscated have none.
     *
     * @param profile the parsed {@code install_profile.json}
     * @param sideName {@code client} or {@code server}
     */
    static Optional<Plan> plan(JsonObject profile, String sideName) {
        if (!profile.has("processors")) {
            return Optional.empty();
        }
        for (JsonElement element : profile.getAsJsonArray("processors")) {
            JsonObject processor = element.getAsJsonObject();
            if (!processor.has("args") || !appliesTo(processor, sideName)) {
                continue;
            }
            List<String> args = strings(processor.getAsJsonArray("args"));
            if (!args.contains(TASK) || !args.contains("--input-mappings")) {
                continue;
            }
            String neoform = value(args, "--neoform-data");
            String patches = value(args, "--apply-patches");
            if (neoform == null || patches == null) {
                return Optional.empty();
            }
            return Optional.of(new Plan(
                    processor.get("jar").getAsString(),
                    stripBrackets(neoform),
                    patchesEntry(profile, patches, sideName)));
        }
        return Optional.empty();
    }

    /** Whether {@code processor} runs for {@code sideName}: an empty or missing {@code sides} list means both. */
    private static boolean appliesTo(JsonObject processor, String sideName) {
        if (!processor.has("sides")) {
            return true;
        }
        JsonArray sides = processor.getAsJsonArray("sides");
        if (sides.isEmpty()) {
            return true;
        }
        return strings(sides).contains(sideName);
    }

    /** {@code {BINPATCH}} becomes the profile's {@code data.BINPATCH.<side>} without its leading slash. */
    private static String patchesEntry(JsonObject profile, String argument, String sideName) {
        String path = argument;
        if (argument.startsWith("{") && argument.endsWith("}")) {
            JsonObject data =
                    profile.getAsJsonObject("data").getAsJsonObject(argument.substring(1, argument.length() - 1));
            path = data.get(sideName).getAsString();
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String stripBrackets(String coordinate) {
        return coordinate.startsWith("[") && coordinate.endsWith("]")
                ? coordinate.substring(1, coordinate.length() - 1)
                : coordinate;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static String value(List<String> args, String option) {
        int index = args.indexOf(option);
        return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : null;
    }

    /**
     * Runs the processor: {@code obfuscated} renamed with {@code mappings} and patched with {@code patches}, written to
     * {@code output}.
     *
     * @param toolsJar NeoForge's {@code installertools} fat jar
     * @param neoformData the NeoForm mappings file ({@code mappings.tsrg.lzma})
     * @param extractLibrariesTo where a server bundler's libraries would go; empty for the jars Plexus Mod Loader passes
     * @param log gets the tool's last lines on failure and one line when it succeeded
     * @throws IOException when the tool cannot start, takes too long or fails; {@code output} does not exist then
     */
    static void run(
            Path toolsJar,
            Path obfuscated,
            Path mappings,
            Path neoformData,
            Path patches,
            Path extractLibrariesTo,
            Path output,
            Consumer<String> log)
            throws IOException {
        // Absolute: the tool is a separate process, which resolves relative paths against its own working directory.
        output = output.toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.createDirectories(extractLibrariesTo);
        Path partial = output.resolveSibling(output.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        Path report = output.resolveSibling(output.getFileName() + ".log");
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java")
                        .toAbsolutePath()
                        .toString(),
                "-jar",
                absolute(toolsJar),
                "--task",
                TASK,
                "--input",
                absolute(obfuscated),
                "--input-mappings",
                absolute(mappings),
                "--output",
                partial.toString(),
                "--extract-libraries-to",
                absolute(extractLibrariesTo),
                "--neoform-data",
                absolute(neoformData),
                "--apply-patches",
                absolute(patches));
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(report.toFile())
                .start();
        try {
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                Files.deleteIfExists(partial);
                throw new IOException("NeoForge's installertools did not finish " + TASK + " in " + TIMEOUT_MINUTES
                        + " minutes (" + report + ")");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while NeoForge's installertools ran " + TASK, e);
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(partial)) {
            Files.deleteIfExists(partial);
            throw new IOException("NeoForge's installertools failed " + TASK + " (exit " + process.exitValue() + "): "
                    + tail(report));
        }
        Files.move(partial, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.accept("NeoForge's installertools made " + output.getFileName() + " in "
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
    }

    private static String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static String tail(Path report) throws IOException {
        // Not readAllLines: the tool writes in the platform's encoding, and a malformed byte must not hide its failure.
        List<String> lines = new String(Files.readAllBytes(report), StandardCharsets.UTF_8)
                .lines()
                .toList();
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size()));
    }
}
