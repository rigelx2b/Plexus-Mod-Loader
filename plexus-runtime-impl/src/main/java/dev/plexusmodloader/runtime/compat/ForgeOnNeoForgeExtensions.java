package dev.plexusmodloader.runtime.compat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Forge on NeoForge, F-N5 (ADR-0051): Forge mods' overrides of the methods Forge's patch adds to Minecraft classes
 * through its extension interfaces ({@code IForgeItem} on {@code Item} and the like). On NeoForge's game such an override
 * is an override only when the game class's hierarchy there (vanilla and NeoForge's own extension interfaces, {@code
 * IItemExtension} and the like) has a method of the same name and descriptor; otherwise the game never calls it, and the
 * mod would run without what the override was for. This check finds those overrides before the mod runs, so that the
 * link check leaves the mod out instead.
 *
 * <p>Each Forge extension interface is read from Forge's {@code universal} jar, with the game class it extends: the
 * return type of its {@code self()}, or, for the interfaces without one, {@link #TARGETS_WITHOUT_SELF} (checked
 * against the Forge releases Plexus Mod Loader pins). A mod method counts when the mod class has that game class among
 * its supertypes and the method has the name and descriptor of one of the interface's methods. Same name and descriptor
 * on NeoForge's side is taken to mean the same thing; it is not compared method by method. Mixin classes are not checked
 * (FML's Mixin and the link check's mixin audit handle them).
 *
 * <p>Reads class files only and defines no class. Thread: not thread-safe; caches parsed classes.
 */
public final class ForgeOnNeoForgeExtensions {

    private static final List<String> PREFIXES =
            List.of("net/minecraftforge/common/extensions/IForge", "net/minecraftforge/client/extensions/IForge");

    /** The game packages whose classes (with NeoForge's) make the game class hierarchy the overrides are checked in. */
    private static final List<String> GAME_PACKAGES = List.of("net/minecraft/", "com/mojang/", "net/neoforged/");

    private static final Set<String> MIXIN_ANNOTATIONS = Set.of("Lorg/spongepowered/asm/mixin/Mixin;");

    /**
     * The game class of each Forge extension interface that has no {@code self()} (Forge {@code 26.1-62.0.9} to {@code
     * 26.3-66.1.0}), by the interface's name: the game class of the same simple name, found in NeoForge's patched game.
     */
    static final Map<String, String> TARGETS_WITHOUT_SELF = Map.of(
            "net/minecraftforge/common/extensions/IForgeBaseRailBlock", "net/minecraft/world/level/block/BaseRailBlock",
            "net/minecraftforge/common/extensions/IForgeFluid", "net/minecraft/world/level/material/Fluid",
            "net/minecraftforge/common/extensions/IForgeLevel", "net/minecraft/world/level/Level",
            "net/minecraftforge/common/extensions/IForgeLevelChunk", "net/minecraft/world/level/chunk/LevelChunk",
            "net/minecraftforge/common/extensions/IForgeMenuType", "net/minecraft/world/inventory/MenuType",
            "net/minecraftforge/common/extensions/IForgePackResources", "net/minecraft/server/packs/PackResources",
            "net/minecraftforge/common/extensions/IForgeRawTagBuilder", "net/minecraft/tags/TagBuilder",
            "net/minecraftforge/common/extensions/IForgeHolderSet", "net/minecraft/core/HolderSet",
            // Only Forge 26.3-66.1.0's has no self(); earlier ones name com/mojang/blaze3d/textures/GpuTexture there.
            "net/minecraftforge/client/extensions/IForgeGpuTexture", "com/mojang/renderpearl/api/textures/GpuTexture");

    /**
     * One Forge extension interface.
     *
     * @param name the interface, as an internal name
     * @param target the game class it extends, as an internal name; empty when neither {@code self()} nor {@link
     *     #TARGETS_WITHOUT_SELF} names one
     * @param methods its instance methods a subclass can override, as name plus descriptor
     */
    public record Extension(String name, Optional<String> target, Set<String> methods) {
        public Extension {
            methods = Set.copyOf(methods);
        }
    }

    /**
     * An override the game does not call.
     *
     * @param modClass the mod class, as an internal name
     * @param method the method, as name plus descriptor
     * @param extension the Forge extension interface that declares it
     * @param target the game class that interface extends
     */
    public record Unfit(String modClass, String method, String extension, String target) {
        @Override
        public String toString() {
            return simpleName(modClass) + "." + method.substring(0, method.indexOf('(')) + " overrides "
                    + simpleName(extension) + "." + method + ", which only MinecraftForge's patch adds to "
                    + simpleName(target) + "; NeoForge's game does not call it";
        }
    }

    private ForgeOnNeoForgeExtensions() {}

    /**
     * Forge's extension interfaces in {@code forgeUniversal} (top-level {@code IForge*} interfaces of its {@code
     * common.extensions} and {@code client.extensions} packages), by name.
     *
     * @throws IOException when the jar or one of the classes cannot be read
     */
    public static List<Extension> extensions(Path forgeUniversal) throws IOException {
        List<Extension> extensions = new ArrayList<>();
        try (JarFile jar = new JarFile(forgeUniversal.toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class")
                        || name.contains("$")
                        || PREFIXES.stream().noneMatch(name::startsWith)) {
                    continue;
                }
                ClassModel model;
                try (InputStream input = jar.getInputStream(entry)) {
                    model = ClassFile.of().parse(input.readAllBytes());
                } catch (IllegalArgumentException e) {
                    throw new IOException(name + " in " + forgeUniversal.getFileName() + ": " + e, e);
                }
                if (model.flags().has(AccessFlag.INTERFACE)) {
                    extensions.add(extension(model));
                }
            }
        }
        extensions.sort(java.util.Comparator.comparing(Extension::name));
        return List.copyOf(extensions);
    }

    private static Extension extension(ClassModel model) {
        String name = model.thisClass().asInternalName();
        Optional<String> target = Optional.ofNullable(TARGETS_WITHOUT_SELF.get(name));
        Set<String> methods = new TreeSet<>();
        for (MethodModel method : model.methods()) {
            String methodName = method.methodName().stringValue();
            String type = method.methodType().stringValue();
            // self() is private in most interfaces and public in some (IForgeFont, IForgeLivingEntity).
            if (methodName.equals("self")
                    && type.startsWith("()L")
                    && !method.flags().has(AccessFlag.STATIC)) {
                target = Optional.of(type.substring(3, type.length() - 1));
                continue;
            }
            if (methodName.startsWith("<")
                    || method.flags().has(AccessFlag.STATIC)
                    || method.flags().has(AccessFlag.PRIVATE)) {
                continue;
            }
            methods.add(methodName + type);
        }
        return new Extension(name, target, methods);
    }

    /**
     * The overrides in {@code modJar} of Forge extension methods that the game class hierarchy read through {@code
     * classes} (NeoForge's patched game, NeoForge's own classes, the vanilla jar and other mods) does not have, one per
     * mod class and method, in class name order. Extensions without a target are skipped.
     *
     * @throws UncheckedIOException when the jar cannot be read
     */
    public static List<Unfit> unfit(
            Path modJar, List<Extension> extensions, Function<String, Optional<byte[]>> classes) {
        if (!Files.isRegularFile(modJar)) {
            return List.of();
        }
        Map<String, List<Extension>> byMethod = new HashMap<>();
        for (Extension extension : extensions) {
            if (extension.target().isEmpty()) {
                continue;
            }
            for (String method : extension.methods()) {
                byMethod.computeIfAbsent(method, key -> new ArrayList<>()).add(extension);
            }
        }
        Map<String, ClassModel> own = read(modJar);
        Hierarchy hierarchy = new Hierarchy(own, classes);
        Map<String, Unfit> found = new TreeMap<>();
        for (ClassModel model : own.values()) {
            if (isMixin(model)) {
                continue;
            }
            String className = model.thisClass().asInternalName();
            Set<String> supertypes = null;
            for (MethodModel method : model.methods()) {
                String methodName = method.methodName().stringValue();
                if (methodName.startsWith("<")
                        || method.flags().has(AccessFlag.STATIC)
                        || method.flags().has(AccessFlag.PRIVATE)) {
                    continue;
                }
                String key = methodName + method.methodType().stringValue();
                List<Extension> candidates = byMethod.get(key);
                if (candidates == null) {
                    continue;
                }
                if (supertypes == null) {
                    supertypes = hierarchy.supertypes(className);
                }
                for (Extension extension : candidates) {
                    String target = extension.target().orElseThrow();
                    if (supertypes.contains(target) && !hierarchy.gameDeclares(supertypes, key)) {
                        found.putIfAbsent(className + " " + key, new Unfit(className, key, extension.name(), target));
                    }
                }
            }
        }
        return List.copyOf(found.values());
    }

    private static Map<String, ClassModel> read(Path modJar) {
        Map<String, ClassModel> classes = new TreeMap<>();
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
                parse(bytes).ifPresent(model -> classes.put(model.thisClass().asInternalName(), model));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + modJar + " to check Forge extension overrides", e);
        }
        return classes;
    }

    private static boolean isMixin(ClassModel model) {
        List<Annotation> annotations = new ArrayList<>();
        model.findAttribute(Attributes.runtimeInvisibleAnnotations())
                .map(RuntimeInvisibleAnnotationsAttribute::annotations)
                .ifPresent(annotations::addAll);
        model.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(RuntimeVisibleAnnotationsAttribute::annotations)
                .ifPresent(annotations::addAll);
        return annotations.stream()
                .anyMatch(annotation ->
                        MIXIN_ANNOTATIONS.contains(annotation.className().stringValue()));
    }

    private static Optional<ClassModel> parse(byte[] bytes) {
        try {
            return Optional.of(ClassFile.of().parse(bytes));
        } catch (IllegalArgumentException e) {
            // The link check reads the same bytes and reports a class file it cannot parse.
            return Optional.empty();
        }
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    /** Supertypes of mod classes, read from the mod's own classes first, then from the given class files. */
    private static final class Hierarchy {

        private final Map<String, ClassModel> own;
        private final Function<String, Optional<byte[]>> classes;
        private final Map<String, Optional<ClassModel>> read = new HashMap<>();

        Hierarchy(Map<String, ClassModel> own, Function<String, Optional<byte[]>> classes) {
            this.own = own;
            this.classes = classes;
        }

        /** Every superclass and superinterface of {@code className} that can be read, transitively. */
        Set<String> supertypes(String className) {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            model(className).ifPresent(model -> enqueue(model, queue));
            while (!queue.isEmpty()) {
                String next = queue.removeFirst();
                if (seen.add(next)) {
                    model(next).ifPresent(model -> enqueue(model, queue));
                }
            }
            return seen;
        }

        /**
         * Whether a game or NeoForge class among {@code supertypes} declares {@code method} (name plus descriptor) as an
         * instance method a subclass overrides.
         */
        boolean gameDeclares(Set<String> supertypes, String method) {
            for (String type : supertypes) {
                if (own.containsKey(type) || GAME_PACKAGES.stream().noneMatch(type::startsWith)) {
                    continue;
                }
                Optional<ClassModel> model = model(type);
                if (model.isEmpty()) {
                    continue;
                }
                for (MethodModel candidate : model.get().methods()) {
                    if ((candidate.methodName().stringValue()
                                            + candidate.methodType().stringValue())
                                    .equals(method)
                            && !candidate.flags().has(AccessFlag.STATIC)
                            && !candidate.flags().has(AccessFlag.PRIVATE)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static void enqueue(ClassModel model, Deque<String> queue) {
            model.superclass().map(ClassEntry::asInternalName).ifPresent(queue::addLast);
            for (ClassEntry face : model.interfaces()) {
                queue.addLast(face.asInternalName());
            }
        }

        private Optional<ClassModel> model(String className) {
            ClassModel ownModel = own.get(className);
            if (ownModel != null) {
                return Optional.of(ownModel);
            }
            return read.computeIfAbsent(
                    className, name -> classes.apply(name).flatMap(ForgeOnNeoForgeExtensions::parse));
        }
    }
}
