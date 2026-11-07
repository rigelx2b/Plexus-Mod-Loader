package dev.plexusmodloader.compat.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/**
 * The steps of Forge's installer that make the renamed game jar of an obfuscated Minecraft version (Forge 1.21.1 and
 * 1.21.11, ADR-0038): {@code installertools} {@code DOWNLOAD_MOJMAPS --sanitize} writes Mojang's mappings in the form
 * Forge's renamer reads, and {@code ForgeAutoRenamingTool} renames the obfuscated jar with them (the installer's
 * {@code MC_OFF}). Forge's binary patches then apply to that jar ({@link ForgePatchSet}), whose classes they check by
 * Adler32, so only Forge's own renaming produces a jar they apply to; Plexus Mod Loader's remapped jar ({@code
 * ADR-0019}) differs in bytes. The versions from 26.1 on are not obfuscated and have no such step.
 *
 * <p>The tools are the jars Forge's {@code install_profile.json} lists with the class paths it gives them (LGPL 2.1,
 * downloaded and checked against the SHA-1s there, never copied into Plexus Mod Loader), each run in a separate JVM as
 * Forge's installer runs it. Their output stays in the cache folder on this machine.
 */
final class ForgeInstallerTools {

    /** The renamer of Forge's installer profiles, and the one of NeoForge's (21.1, 20.4: same steps, same arguments). */
    private static final List<String> RENAMERS =
            List.of("net.minecraftforge:ForgeAutoRenamingTool:", "net.neoforged:AutoRenamingTool:");

    private static final long TIMEOUT_MINUTES = 5;
    private static final int LOG_LINES = 12;

    /** Gives the downloaded, checked jar for a coordinate of the installer profile. */
    @FunctionalInterface
    interface LibrarySource {
        Path get(String name) throws IOException;
    }

    private ForgeInstallerTools() {}

    /**
     * One processor of the profile: the jars it runs from and its arguments with {@code {NAME}} placeholders.
     *
     * @param classPath the coordinates of the jars on its class path, the tool's own first
     * @param arguments the processor's {@code args}
     */
    record Step(List<String> classPath, List<String> arguments) {
        Step {
            classPath = List.copyOf(classPath);
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * The renaming pipeline of one side: every processor of the profile that works on the game jar except the binary
     * patcher (which {@link ForgePatchSet} does) and the server's file extraction, in the profile's order.
     *
     * @param steps the processors; for Minecraft 1.20.x also {@code MCP_DATA} and {@code MERGE_MAPPING}
     * @param rename the {@code ForgeAutoRenamingTool} step, whose {@code --output} is the renamed game jar
     */
    record Plan(List<Step> steps, Step rename) {
        Plan {
            steps = List.copyOf(steps);
        }
    }

    /**
     * The renaming pipeline of {@code sideName} ({@code client} or {@code server}), when the profile has a {@code
     * ForgeAutoRenamingTool} processor for that side (Forge 1.20.x: names are SRG for members, 1.21.x: Mojang's).
     */
    static Optional<Plan> plan(JsonObject profile, String sideName) {
        if (!profile.has("processors")) {
            return Optional.empty();
        }
        List<Step> steps = new ArrayList<>();
        Step rename = null;
        for (JsonElement element : profile.getAsJsonArray("processors")) {
            JsonObject processor = element.getAsJsonObject();
            if (!processor.has("args") || !appliesTo(processor, sideName)) {
                continue;
            }
            List<String> args = strings(processor.getAsJsonArray("args"));
            String jar = processor.get("jar").getAsString();
            if (jar.contains(":binarypatcher:") || args.contains("EXTRACT_FILES") || args.contains("BUNDLER_EXTRACT")) {
                continue;
            }
            Step step = new Step(classPath(processor), args);
            steps.add(step);
            if (RENAMERS.stream().anyMatch(jar::startsWith)) {
                rename = step;
            }
        }
        return rename == null ? Optional.empty() : Optional.of(new Plan(steps, rename));
    }

    private static List<String> classPath(JsonObject processor) {
        List<String> names = new ArrayList<>();
        names.add(processor.get("jar").getAsString());
        if (processor.has("classpath")) {
            for (String name : strings(processor.getAsJsonArray("classpath"))) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static boolean appliesTo(JsonObject processor, String sideName) {
        if (!processor.has("sides") || processor.getAsJsonArray("sides").isEmpty()) {
            return true;
        }
        return strings(processor.getAsJsonArray("sides")).contains(sideName);
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    /**
     * Runs the pipeline: {@code obfuscated} renamed into {@code output} (Mojang's mappings are fetched on the way).
     *
     * @param libraries gives the downloaded, checked jar for a coordinate of the profile
     * @param work where the tools' reports go
     * @throws IOException when a tool cannot start, takes too long or fails; {@code output} does not exist then
     */
    static void run(
            Plan plan,
            String minecraftVersion,
            String sideName,
            Path obfuscated,
            Path output,
            Path work,
            LibrarySource libraries,
            java.util.function.Consumer<String> log)
            throws IOException {
        Path absoluteOutput = output.toAbsolutePath().normalize();
        Files.createDirectories(absoluteOutput.getParent());
        Files.createDirectories(work);
        Path partial = absoluteOutput.resolveSibling(absoluteOutput.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        String game = obfuscated.toAbsolutePath().normalize().toString();
        Map<String, String> values = new java.util.HashMap<>();
        values.put("SIDE", sideName);
        values.put("MINECRAFT_JAR", game);
        values.put("MC_UNPACKED", game);
        // The renamed jar goes to the output; every other value the profile names (mappings, merged mappings, ...) is a
        // file in the work folder that one step writes and a later one reads.
        int at = plan.rename().arguments().indexOf("--output");
        if (at >= 0 && at + 1 < plan.rename().arguments().size()) {
            String key = plan.rename().arguments().get(at + 1);
            if (key.startsWith("{") && key.endsWith("}")) {
                values.put(key.substring(1, key.length() - 1), partial.toString());
            }
        }
        for (Step step : plan.steps()) {
            for (String argument : step.arguments()) {
                if (argument.startsWith("{") && argument.endsWith("}")) {
                    String key = argument.substring(1, argument.length() - 1);
                    if (!values.containsKey(key) && !key.equals("ROOT") && !key.equals("INSTALLER")) {
                        values.put(
                                key,
                                work.resolve("data-" + key).toAbsolutePath().toString());
                    }
                }
            }
        }
        long start = System.nanoTime();
        int number = 0;
        for (Step step : plan.steps()) {
            execute(step, values, libraries, work.resolve("step-" + (++number) + ".log"));
        }
        if (!Files.isRegularFile(partial)) {
            throw new IOException("Forge's ForgeAutoRenamingTool wrote no jar for Minecraft " + minecraftVersion);
        }
        // Forge 1.20.1 splits the game jar into classes (renamed) and everything else (MC_EXTRA: lang files, data,
        // assets);
        // Forge's own launch puts both on the module path. Here they are one jar, so the extra files join the renamed
        // one.
        Path extra = work.resolve("data-MC_EXTRA");
        if (Files.isRegularFile(extra)) {
            addMissingEntries(partial, extra);
        }
        Files.move(partial, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
        log.accept("Forge's installer tools renamed " + obfuscated.getFileName() + " to " + absoluteOutput.getFileName()
                + " in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
    }

    /**
     * Adds to {@code jar} the entries of {@code extra} that it does not have, except the manifest and Mojang's signature
     * files (the classes in {@code jar} are not the signed ones).
     */
    static void addMissingEntries(Path jar, Path extra) throws IOException {
        Path merged = jar.resolveSibling(jar.getFileName() + ".merged");
        try (JarFile first = new JarFile(jar.toFile());
                JarFile second = new JarFile(extra.toFile());
                java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(Files.newOutputStream(merged))) {
            java.util.Set<String> written = new java.util.HashSet<>();
            for (JarFile source : List.of(first, second)) {
                var entries = source.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    boolean signature = name.startsWith("META-INF/")
                            && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"));
                    if (signature || (source == second && name.equals("META-INF/MANIFEST.MF")) || !written.add(name)) {
                        continue;
                    }
                    java.util.jar.JarEntry copy = new java.util.jar.JarEntry(name);
                    copy.setTime(entry.getTime());
                    out.putNextEntry(copy);
                    if (!entry.isDirectory()) {
                        try (java.io.InputStream in = source.getInputStream(entry)) {
                            in.transferTo(out);
                        }
                    }
                    out.closeEntry();
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(merged);
            throw e;
        }
        Files.move(merged, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void execute(Step step, Map<String, String> values, LibrarySource libraries, Path report)
            throws IOException {
        List<Path> jars = new ArrayList<>();
        for (String name : step.classPath()) {
            jars.add(libraries.get(name).toAbsolutePath().normalize());
        }
        String mainClass;
        try (JarFile tool = new JarFile(jars.getFirst().toFile())) {
            mainClass = tool.getManifest() == null
                    ? null
                    : tool.getManifest().getMainAttributes().getValue("Main-Class");
        }
        if (mainClass == null) {
            throw new IOException(jars.getFirst().getFileName() + " names no Main-Class");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java")
                .toAbsolutePath()
                .toString());
        command.add("-cp");
        command.add(String.join(
                File.pathSeparator, jars.stream().map(Path::toString).toList()));
        command.add(mainClass);
        for (String argument : step.arguments()) {
            command.add(substitute(argument, values, libraries));
        }
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(report.toFile())
                .start();
        try {
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException(jars.getFirst().getFileName() + " did not finish in " + TIMEOUT_MINUTES
                        + " minutes (" + report + ")");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while " + jars.getFirst().getFileName() + " ran", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException(
                    jars.getFirst().getFileName() + " failed (exit " + process.exitValue() + "): " + tail(report));
        }
    }

    /** {@code {NAME}} replaced by its value; an argument without a placeholder as it is. */
    static String substitute(String argument, Map<String, String> values, LibrarySource libraries) throws IOException {
        if (argument.startsWith("[") && argument.endsWith("]")) {
            // A Maven coordinate of the profile, such as the MCP config zip: the downloaded file.
            return libraries
                    .get(argument.substring(1, argument.length() - 1))
                    .toAbsolutePath()
                    .normalize()
                    .toString();
        }
        if (argument.startsWith("{") && argument.endsWith("}")) {
            String value = values.get(argument.substring(1, argument.length() - 1));
            if (value == null) {
                throw new IOException("Forge's installer profile uses the placeholder " + argument
                        + ", which Plexus Mod Loader does not know");
            }
            return value;
        }
        return argument;
    }

    private static String tail(Path report) throws IOException {
        // Not readAllLines: the tool writes in the platform's encoding, and a malformed byte must not hide its failure.
        List<String> lines = new String(Files.readAllBytes(report), StandardCharsets.UTF_8)
                .lines()
                .toList();
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size()));
    }
}
