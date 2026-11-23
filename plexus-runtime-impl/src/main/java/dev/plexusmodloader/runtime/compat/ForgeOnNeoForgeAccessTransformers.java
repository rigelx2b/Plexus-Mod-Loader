package dev.plexusmodloader.runtime.compat;

import dev.plexusmodloader.api.runtime.GameSide;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Forge on NeoForge (ADR-0051): whether a MinecraftForge mod's access transformer ({@code META-INF/accesstransformer.cfg})
 * fits NeoForge's game. NeoForge's FML reads that file as it reads a NeoForge mod's (its default access transformer
 * path when {@code mods.toml} names none) into its own access transformer processor, so the lines are applied by
 * NeoForge's engine to the classes of FML's game layer. This check, run before launch, leaves out a mod with a line that
 * would not apply as written: a line that is not {@code <access> <class> [<member>]} with {@code public} or {@code
 * protected} and an optional {@code -f} ({@code +f} is in no corpus line and not checked); a class that is not in the classes FML transforms (NeoForge's
 * patched game, the other mods, the mod itself), or is only in the loader libraries; a field, method or constructor the
 * class does not declare (such as a member only MinecraftForge's patches add). On a dedicated server, a class of the
 * game's client-only packages is not there and its lines are not checked: the class never loads, as on MinecraftForge.
 * Access transformers named in {@code mods.toml} ({@code [[accessTransformers]]}) are not read, so a mod with them is
 * left out too.
 *
 * <p>Thread: stateless.
 */
public final class ForgeOnNeoForgeAccessTransformers {

    static final String FILE = "META-INF/accesstransformer.cfg";

    private static final Pattern ACCESS = Pattern.compile("(public|protected)(-f)?");
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern MODS_TOML_ACCESS_TRANSFORMERS =
            Pattern.compile("(?m)^\\s*\\[\\[\\s*accessTransformers\\s*]]");

    /**
     * Packages of the game that only its client has, as the link check knows them (Realms among them); their classes are
     * absent from a dedicated server's game.
     */
    private static final List<String> CLIENT_ONLY_PACKAGES = ModLinkVerifier.CLIENT_PACKAGES;

    private ForgeOnNeoForgeAccessTransformers() {}

    /**
     * What keeps {@code jar}'s access transformer from applying as written on {@code side}, one line per problem
     * ({@code accesstransformer.cfg line <n>: <what>}); empty when it fits or the jar has none.
     *
     * @param transformedClasses the class file of an internal name in the classes FML transforms (the host's patched
     *     game, the other mods), when they have the class; the jar's own classes are looked up after them
     * @param libraryClasses the class file of an internal name in the loader libraries, which FML does not transform
     * @throws IOException when the jar cannot be read
     */
    public static List<String> unfit(
            Path jar,
            GameSide side,
            Function<String, Optional<byte[]>> transformedClasses,
            Function<String, Optional<byte[]>> libraryClasses)
            throws IOException {
        List<String> problems = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Optional<String> modsToml = text(file, "META-INF/mods.toml");
            if (modsToml.isPresent()
                    && MODS_TOML_ACCESS_TRANSFORMERS.matcher(modsToml.get()).find()) {
                problems.add("[[accessTransformers]] in mods.toml is not checked");
            }
            Optional<String> config = text(file, FILE);
            if (config.isEmpty()) {
                return List.copyOf(problems);
            }
            String[] lines = config.get().split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String problem = check(
                        lines[i],
                        side,
                        name -> {
                            Optional<byte[]> game = transformedClasses.apply(name);
                            return game.isPresent() ? game : ownClass(file, name);
                        },
                        libraryClasses);
                if (problem != null) {
                    problems.add("accesstransformer.cfg line " + (i + 1) + ": " + problem);
                }
            }
        }
        return List.copyOf(problems);
    }

    /** What keeps one access transformer line from applying, or {@code null} when it applies or is empty. */
    static String check(
            String line,
            GameSide side,
            Function<String, Optional<byte[]>> transformedClasses,
            Function<String, Optional<byte[]>> libraryClasses) {
        int comment = line.indexOf('#');
        String entry = (comment >= 0 ? line.substring(0, comment) : line).strip();
        if (entry.isEmpty()) {
            return null;
        }
        String[] parts = entry.split("\\s+");
        if (parts.length < 2 || parts.length > 3) {
            return "'" + entry + "' is not <access> <class> [<member>]";
        }
        if (!ACCESS.matcher(parts[0]).matches()) {
            return "access '" + parts[0] + "' is not checked (only public or protected, with or without -f)";
        }
        if (!CLASS_NAME.matcher(parts[1]).matches()) {
            return "'" + parts[1] + "' is not a class name";
        }
        String owner = parts[1].replace('.', '/');
        Optional<byte[]> bytes = transformedClasses.apply(owner);
        if (bytes.isEmpty()) {
            if (libraryClasses.apply(owner).isPresent()) {
                return "class " + parts[1] + " is in the loader libraries, which FML's access transformers do not"
                        + " transform";
            }
            if (side == GameSide.DEDICATED_SERVER
                    && CLIENT_ONLY_PACKAGES.stream().anyMatch(owner::startsWith)) {
                return null;
            }
            return "class " + parts[1] + " is not in the game";
        }
        if (parts.length == 2) {
            return null;
        }
        ClassModel model;
        try {
            model = ClassFile.of().parse(bytes.get());
        } catch (IllegalArgumentException e) {
            return "class " + parts[1] + " cannot be read: " + e.getMessage();
        }
        return member(model, parts[1], parts[2]);
    }

    private static String member(ClassModel model, String className, String member) {
        if (member.equals("*") || member.equals("*()")) {
            return null;
        }
        int open = member.indexOf('(');
        if (open < 0) {
            if (!IDENTIFIER.matcher(member).matches()) {
                return "'" + member + "' is not a field name";
            }
            boolean declared =
                    model.fields().stream().anyMatch(field -> field.fieldName().equalsString(member));
            return declared ? null : "field " + member + " is not in " + className;
        }
        String name = member.substring(0, open);
        String descriptor = member.substring(open);
        if (!name.equals("<init>") && !IDENTIFIER.matcher(name).matches()) {
            return "'" + member + "' is not a method";
        }
        try {
            MethodTypeDesc.ofDescriptor(descriptor);
        } catch (IllegalArgumentException e) {
            return "'" + member + "' has no valid descriptor";
        }
        boolean declared = model.methods().stream()
                .anyMatch(method -> method.methodName().equalsString(name)
                        && method.methodType().equalsString(descriptor));
        return declared ? null : "method " + member + " is not in " + className;
    }

    private static Optional<String> text(JarFile file, String name) throws IOException {
        JarEntry entry = file.getJarEntry(name);
        if (entry == null) {
            return Optional.empty();
        }
        try (InputStream input = file.getInputStream(entry)) {
            return Optional.of(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Optional<byte[]> ownClass(JarFile file, String internalName) {
        JarEntry entry = file.getJarEntry(internalName + ".class");
        if (entry == null) {
            return Optional.empty();
        }
        try (InputStream input = file.getInputStream(entry)) {
            return Optional.of(input.readAllBytes());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to read " + internalName + " from " + file.getName(), e);
        }
    }
}
