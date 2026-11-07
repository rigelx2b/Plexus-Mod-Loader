package dev.plexusmodloader.runtime.compat;

import dev.plexusmodloader.compat.bridge.GameClassBytes;
import dev.plexusmodloader.internal.transform.MethodTarget;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Finds the classes of a mod jar that override a method which the host loader's patch made {@code final} while vanilla
 * declares the same method without {@code final}, before mods load next to a host loader (ADR-0030, ADR-0047).
 * MinecraftForge 47.4.28 and 49.2.13 make {@code Ingredient.toNetwork(FriendlyByteBuf)} {@code final} (their access
 * transformer line {@code public+f}); Fabric API's {@code CustomIngredientImpl} overrides it, and the JVM refuses to
 * define such a class ({@code IncompatibleClassChangeError}), stopping the game when Fabric API's recipe module
 * initializes. Walks each mod class's superclasses through the jar's own classes to the first game class ({@code
 * net/minecraft/} or {@code com/mojang/}), then walks the game class's superclasses.
 *
 * <p>Reads class files only (the mod jar, the patched game through {@link GameClassBytes}, and the vanilla jar through
 * a function) and defines no class. Not thread-safe; caches parsed game classes.
 */
final class HostFinalOverrides {

    private static final List<String> GAME_PACKAGES = List.of("net/minecraft/", "com/mojang/");

    /**
     * One mod class that overrides one method the host's patch made {@code final}.
     *
     * @param modClass the mod class, as a JVM internal name
     * @param method the game method it overrides, in the game class that declares it
     */
    record FinalOverride(String modClass, MethodTarget method) {}

    /** What this check reads of one mod class: name, superclass, and overridable methods as name plus descriptor. */
    private record ModClass(String name, String superName, List<String> methods) {}

    private final GameClassBytes patched;
    private final Function<String, Optional<byte[]>> vanilla;
    private final Map<String, Optional<ClassModel>> patchedModels = new HashMap<>();
    private final Map<String, Optional<ClassModel>> vanillaModels = new HashMap<>();

    /**
     * @param patched the classes of the host's patched game (and, after them, the JDK's and libraries')
     * @param vanilla reads a class of the vanilla game jar by internal name; empty when it has none
     */
    HostFinalOverrides(GameClassBytes patched, Function<String, Optional<byte[]>> vanilla) {
        this.patched = patched;
        this.vanilla = vanilla;
    }

    /**
     * The methods the classes of {@code modJar} override that the host's patch made {@code final} and vanilla does not,
     * one per mod class and method, in class name order. Static and private methods and constructors of the mod are
     * skipped, and so are game methods that are private, static, or package-private in another package (those are not
     * overridden). Empty when {@code modJar} is not a regular file.
     *
     * @throws UncheckedIOException when the jar cannot be read
     */
    List<FinalOverride> find(Path modJar) {
        if (!Files.isRegularFile(modJar)) {
            return List.of();
        }
        Map<String, ModClass> classes = new HashMap<>();
        try (JarFile jar = new JarFile(modJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream input = jar.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                ClassModel model;
                try {
                    model = ClassFile.of().parse(bytes);
                } catch (IllegalArgumentException e) {
                    // The link check reads the same bytes and reports a class file it cannot parse.
                    continue;
                }
                List<String> methods = new ArrayList<>();
                for (MethodModel method : model.methods()) {
                    String methodName = method.methodName().stringValue();
                    if (methodName.startsWith("<")
                            || method.flags().has(AccessFlag.STATIC)
                            || method.flags().has(AccessFlag.PRIVATE)) {
                        continue;
                    }
                    methods.add(methodName + method.methodType().stringValue());
                }
                String className = model.thisClass().asInternalName();
                classes.put(
                        className,
                        new ModClass(
                                className,
                                model.superclass()
                                        .map(entryClass -> entryClass.asInternalName())
                                        .orElse(null),
                                List.copyOf(methods)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    String.format(Locale.ROOT, "Failed to read %s to check for final method overrides", modJar), e);
        }
        Map<String, FinalOverride> found = new LinkedHashMap<>();
        for (ModClass modClass : classes.values().stream()
                .sorted(java.util.Comparator.comparing(ModClass::name))
                .toList()) {
            Optional<String> gameSuper = gameSuperclass(modClass, classes);
            if (gameSuper.isEmpty()) {
                continue;
            }
            for (String method : modClass.methods()) {
                hostMadeFinal(gameSuper.get(), method, packageOf(modClass.name()))
                        .ifPresent(target -> found.putIfAbsent(
                                modClass.name() + " " + target, new FinalOverride(modClass.name(), target)));
            }
        }
        return List.copyOf(found.values());
    }

    /** The first game class above {@code modClass}, reached through the jar's own classes; empty when there is none. */
    private Optional<String> gameSuperclass(ModClass modClass, Map<String, ModClass> classes) {
        Set<String> seen = new HashSet<>();
        String current = modClass.superName();
        while (current != null && seen.add(current)) {
            ModClass own = classes.get(current);
            if (own == null) {
                return isGameClass(current) && patchedModel(current).isPresent()
                        ? Optional.of(current)
                        : Optional.empty();
            }
            current = own.superName();
        }
        return Optional.empty();
    }

    /**
     * Method of {@code gameClass} or superclasses that {@code method} overrides when final in patched game, not
     * vanilla; empty otherwise.
     */
    private Optional<MethodTarget> hostMadeFinal(String gameClass, String method, String modPackage) {
        Set<String> seen = new LinkedHashSet<>();
        String current = gameClass;
        while (current != null && seen.add(current)) {
            Optional<ClassModel> model = patchedModel(current);
            if (model.isEmpty()) {
                return Optional.empty();
            }
            Optional<MethodModel> declared = declared(model.get(), method);
            if (declared.isPresent()) {
                MethodModel found = declared.get();
                if (found.flags().has(AccessFlag.PRIVATE) || found.flags().has(AccessFlag.STATIC)) {
                    // Not overridden: a subclass's method of that name is a method of its own.
                    current = model.get()
                            .superclass()
                            .map(entry -> entry.asInternalName())
                            .orElse(null);
                    continue;
                }
                boolean packagePrivate =
                        !found.flags().has(AccessFlag.PUBLIC) && !found.flags().has(AccessFlag.PROTECTED);
                if (!found.flags().has(AccessFlag.FINAL)
                        || (packagePrivate && !packageOf(current).equals(modPackage))) {
                    return Optional.empty();
                }
                String owner = current;
                boolean vanillaNotFinal = vanillaModel(owner)
                        .flatMap(vanillaClass -> declared(vanillaClass, method))
                        .map(vanillaMethod -> !vanillaMethod.flags().has(AccessFlag.FINAL))
                        .orElse(false);
                if (!vanillaNotFinal) {
                    return Optional.empty();
                }
                int split = method.indexOf('(');
                return Optional.of(new MethodTarget(owner, method.substring(0, split), method.substring(split)));
            }
            current = model.get()
                    .superclass()
                    .map(entry -> entry.asInternalName())
                    .orElse(null);
        }
        return Optional.empty();
    }

    private static Optional<MethodModel> declared(ClassModel model, String method) {
        for (MethodModel candidate : model.methods()) {
            if ((candidate.methodName().stringValue() + candidate.methodType().stringValue()).equals(method)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<ClassModel> patchedModel(String internalName) {
        return patchedModels.computeIfAbsent(
                internalName, name -> patched.classBytes(name).flatMap(HostFinalOverrides::parse));
    }

    private Optional<ClassModel> vanillaModel(String internalName) {
        return vanillaModels.computeIfAbsent(
                internalName, name -> vanilla.apply(name).flatMap(HostFinalOverrides::parse));
    }

    private static Optional<ClassModel> parse(byte[] bytes) {
        try {
            return Optional.of(ClassFile.of().parse(bytes));
        } catch (IllegalArgumentException e) {
            // A class this check cannot read is one it cannot judge; the link check and the planner report it.
            return Optional.empty();
        }
    }

    private static boolean isGameClass(String internalName) {
        return GAME_PACKAGES.stream().anyMatch(internalName::startsWith);
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    /**
     * The class file {@code internalName} of {@code jar}, or empty when the jar has no such entry.
     *
     * @throws UncheckedIOException when the entry cannot be read
     */
    static Optional<byte[]> readClass(JarFile jar, String internalName) {
        ZipEntry entry = jar.getEntry(internalName + ".class");
        if (entry == null) {
            return Optional.empty();
        }
        try (InputStream input = jar.getInputStream(entry)) {
            return Optional.of(input.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    String.format(Locale.ROOT, "Failed to read %s from %s", entry.getName(), jar.getName()), e);
        }
    }
}
