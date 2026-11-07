package dev.plexusmodloader.installer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A game jar of an obfuscated version (before 26.1) remapped to Mojang names on the player's machine
 * ({@link MojangJarRemapper}, ADR-0019), kept next to a {@code .key} file that records what it was made from: the
 * SHA-1 of the obfuscated jar, the SHA-1 of the mappings, the {@linkplain MojangJarRemapper#REVISION remapper
 * revision} and the SHA-1 of the result. It is made again only when one of the first three changed or the result no
 * longer has its SHA-1, so a second install, or a reinstall for a new Plexus Mod Loader version with the same game
 * jar, mappings and remapper, takes the cached jar.
 *
 * <p>The jar stays in the launcher or server folder of this machine (ADR-0019 point 3): the installer does not
 * contain it, and neither the mappings nor the jar are uploaded anywhere.
 *
 * @param jar the remapped jar
 * @param sha1 its SHA-1
 * @param size its size in bytes
 * @param result what the remap did, or null if the cached jar was reused
 */
record RemappedGameJar(Path jar, String sha1, long size, MojangJarRemapper.Result result) {

    /** Whether the cached jar was used instead of remapping. */
    boolean reused() {
        return result == null;
    }

    /**
     * Remaps {@code obfuscatedJar} into {@code output} unless the cached one was made from the same inputs. {@code
     * key} is {@link #key(String, String)} of the two inputs' SHA-1, which the caller already has, so they are not
     * hashed again. {@code libraries} are the jar's libraries, for computing stack map frames.
     *
     * @throws InstallerException when the remap fails or its result does not pass the check; no jar is left at {@code
     *     output} then
     */
    static RemappedGameJar ensure(
            Path obfuscatedJar,
            Path mappingsFile,
            String key,
            List<Path> libraries,
            Path output,
            Consumer<String> progress)
            throws InstallerException, IOException {
        Path keyFile = keyFile(output);
        if (Files.isRegularFile(output) && Files.isRegularFile(keyFile)) {
            String[] recorded =
                    Files.readString(keyFile, StandardCharsets.UTF_8).split("\n", -1);
            if (recorded.length >= 4 && (recorded[0] + "\n" + recorded[1] + "\n" + recorded[2] + "\n").equals(key)) {
                String actual = VerifiedDownloads.sha1(output);
                if (recorded[3].equals("output=" + actual)) {
                    return new RemappedGameJar(output, actual, Files.size(output), null);
                }
            }
        }
        Files.deleteIfExists(keyFile);
        Files.deleteIfExists(output);
        MojangMappings mappings;
        MojangJarRemapper.Result result;
        long start = System.nanoTime();
        try {
            mappings = MojangMappings.read(mappingsFile);
            progress.accept("Remapping " + obfuscatedJar.getFileName() + " to Mojang names (" + mappings.classCount()
                    + " classes in the mappings); this takes a few seconds and stays on this computer.");
            result = MojangJarRemapper.remap(obfuscatedJar, mappings, libraries, output);
        } catch (OutOfMemoryError e) {
            Files.deleteIfExists(output);
            throw new InstallerException("The installer ran out of memory remapping " + obfuscatedJar.getFileName()
                    + ". Start it with more memory, such as java -Xmx2G -jar PlexusModLoader-<version>.jar.");
        } catch (RuntimeException e) {
            Files.deleteIfExists(output);
            throw new InstallerException(
                    "Remapping " + obfuscatedJar.getFileName() + " to Mojang names failed: " + e.getMessage()
                            + ". The mappings may not belong to this jar; delete " + mappingsFile
                            + " and the jar and install again.",
                    e);
        }
        List<String> problems = RemappedJarCheck.problems(output, mappings, result);
        if (!problems.isEmpty()) {
            Files.deleteIfExists(output);
            throw new InstallerException("The remapped " + obfuscatedJar.getFileName() + " failed its check: "
                    + String.join("; ", problems) + ". Nothing was installed from it.");
        }
        String sha1 = VerifiedDownloads.sha1(output);
        Files.writeString(keyFile, key + "output=" + sha1 + "\n", StandardCharsets.UTF_8);
        progress.accept("Remapped " + result.classCount() + " classes in "
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
        return new RemappedGameJar(output, sha1, Files.size(output), result);
    }

    /**
     * The cache key: three lines, the SHA-1 of the jar and of the mappings and the remapper revision, each ending in
     * a line feed.
     */
    static String key(Path obfuscatedJar, Path mappingsFile) throws IOException {
        return key(VerifiedDownloads.sha1(obfuscatedJar), VerifiedDownloads.sha1(mappingsFile));
    }

    /**
     * The same key from SHA-1 values already known, in lower-case hexadecimal, such as the ones {@link
     * VerifiedDownloads#fetch} just checked the files against.
     */
    static String key(String jarSha1, String mappingsSha1) {
        return "jar=" + jarSha1 + "\nmappings=" + mappingsSha1 + "\nremapper=" + MojangJarRemapper.REVISION + "\n";
    }

    /** Eight hex digits of the key, for a file name that changes with the inputs. */
    static String shortKey(String key) {
        return VerifiedDownloads.sha1(key).substring(0, 8);
    }

    static Path keyFile(Path jar) {
        String name = jar.getFileName().toString();
        return jar.resolveSibling(name.substring(0, name.length() - ".jar".length()) + ".key");
    }

    /**
     * Checks a remapped jar, a subset of the build's {@code RemappedJarCheck} that needs no libraries and no second
     * JVM: the build also resolves every member reference and loads every class (the {@code remapMinecraft} task),
     * which is checked once per Minecraft version before a release, not on every player's machine.
     */
    static final class RemappedJarCheck {

        private static final int EXAMPLES = 5;

        private RemappedJarCheck() {}

        /** What is wrong with the remapped jar, empty if nothing. */
        static List<String> problems(Path remappedJar, MojangMappings mappings, MojangJarRemapper.Result result) {
            List<String> problems = new ArrayList<>();
            Map<String, byte[]> classes = MojangJarRemapper.readClasses(remappedJar);
            if (classes.isEmpty()) {
                return List.of("it has no classes");
            }
            if (result.renamedClassCount() == 0) {
                problems.add("the mappings list none of its " + result.classCount() + " classes");
            }
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(remappedJar.toFile())) {
                // Plexus Mod Loader identifies the game by this file (PLEXUS-BOOTSTRAP-001).
                if (jar.getEntry("version.json") == null) {
                    problems.add("it has no version.json");
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + remappedJar, e);
            }
            Set<String> identity = new HashSet<>();
            for (String obfuscated : mappings.obfuscatedClasses()) {
                if (mappings.className(obfuscated).equals(obfuscated)) {
                    identity.add(obfuscated);
                }
            }
            Set<String> defaultPackage = new TreeSet<>();
            Set<String> duplicates = new TreeSet<>();
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                String name = entry.getKey();
                if (!name.contains("/") && !identity.contains(name)) {
                    defaultPackage.add(name);
                }
                Set<String> members = new HashSet<>();
                new ClassReader(entry.getValue())
                        .accept(
                                new ClassVisitor(Opcodes.ASM9) {
                                    @Override
                                    public FieldVisitor visitField(
                                            int access,
                                            String field,
                                            String descriptor,
                                            String signature,
                                            Object value) {
                                        if (!members.add("#" + field + " " + descriptor)) {
                                            duplicates.add(name + "#" + field);
                                        }
                                        return null;
                                    }

                                    @Override
                                    public MethodVisitor visitMethod(
                                            int access,
                                            String method,
                                            String descriptor,
                                            String signature,
                                            String[] exceptions) {
                                        if (!members.add(method + descriptor)) {
                                            duplicates.add(name + "." + method + descriptor);
                                        }
                                        return null;
                                    }
                                },
                                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            if (!defaultPackage.isEmpty()) {
                problems.add(defaultPackage.size() + " classes keep their obfuscated names, such as "
                        + defaultPackage.stream().limit(EXAMPLES).toList());
            }
            if (!duplicates.isEmpty()) {
                problems.add(duplicates.size() + " members are declared twice, such as "
                        + duplicates.stream().limit(EXAMPLES).toList());
            }
            return problems;
        }
    }
}
