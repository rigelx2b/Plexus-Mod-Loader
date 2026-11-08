package dev.plexusmodloader.compat.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Enum constructors the host loader's patch made {@code private} that the obfuscated vanilla class has package-private
 * (ADR-0021 G8): MinecraftForge 1.20.x's patched classes are compiled by {@code javac}, which makes an enum's
 * constructors private. A Fabric or Quilt mod that adds a constant with a body to such an enum puts a subclass in the
 * enum's package that calls its constructor, as vanilla allows (Metal Pipe 1.0.5's {@code EnumWidenerPlugin} and its
 * {@code EnchantmentCategory$MetalPipeTarget}); on the patched class that call stopped the game with {@code
 * IllegalAccessError}. Such a constructor gets vanilla's package access back, before the mods' Mixin reads the class.
 * Only constructors: no call changes where it goes, and code that could call it before can still.
 *
 * <p>Thread: any game class loading thread; the vanilla jar is opened once, on first use.
 */
final class HostEnumConstructors {

    private final Function<String, Optional<byte[]>> vanilla;
    private final Consumer<String> report;
    /** The classes already reported: Mixin reads a class before it is defined. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /** {@code vanilla} gives the bytes of a vanilla class by internal name; {@code report} logs what was opened. */
    HostEnumConstructors(Function<String, Optional<byte[]>> vanilla, Consumer<String> report) {
        this.vanilla = vanilla;
        this.report = report;
    }

    /** Reads the vanilla classes from {@code vanillaJar}, which stays open from first use until the JVM exits. */
    static HostEnumConstructors of(Path vanillaJar, Consumer<String> report) {
        return new HostEnumConstructors(new VanillaJar(vanillaJar)::classBytes, report);
    }

    /** {@code bytes} of class {@code className} with the narrowed enum constructors opened again. */
    byte[] apply(String className, byte[] bytes) {
        if (!className.startsWith("net.minecraft.") && !className.startsWith("net/minecraft/")) {
            return bytes;
        }
        ClassFile classFile = ClassFile.of();
        ClassModel model = classFile.parse(bytes);
        if (!model.flags().has(AccessFlag.ENUM)
                || model.methods().stream().noneMatch(HostEnumConstructors::privateInit)) {
            return bytes;
        }
        String internalName = model.thisClass().asInternalName();
        Optional<byte[]> vanillaBytes = vanilla.apply(internalName);
        if (vanillaBytes.isEmpty()) {
            return bytes;
        }
        Set<String> open = new HashSet<>();
        for (MethodModel method : classFile.parse(vanillaBytes.get()).methods()) {
            if (method.methodName().equalsString("<init>") && !method.flags().has(AccessFlag.PRIVATE)) {
                open.add(method.methodType().stringValue());
            }
        }
        Set<String> opened = new HashSet<>();
        for (MethodModel method : model.methods()) {
            if (privateInit(method) && open.contains(method.methodType().stringValue())) {
                opened.add(method.methodType().stringValue());
            }
        }
        if (opened.isEmpty()) {
            return bytes;
        }
        if (reported.add(internalName)) {
            report.accept(internalName + ".<init>" + opened);
        }
        return classFile.transformClass(model, (builder, element) -> {
            if (element instanceof MethodModel method
                    && privateInit(method)
                    && opened.contains(method.methodType().stringValue())) {
                builder.withMethod(
                        method.methodName().stringValue(),
                        method.methodTypeSymbol(),
                        method.flags().flagsMask() & ~ClassFile.ACC_PRIVATE,
                        methodBuilder -> method.forEach(methodElement -> {
                            if (!(methodElement instanceof java.lang.classfile.AccessFlags)) {
                                methodBuilder.with(methodElement);
                            }
                        }));
            } else {
                builder.with(element);
            }
        });
    }

    private static boolean privateInit(MethodModel method) {
        return method.methodName().equalsString("<init>") && method.flags().has(AccessFlag.PRIVATE);
    }

    /** The vanilla game jar, opened on first use. */
    private static final class VanillaJar {

        private final Path path;
        private JarFile jar;
        private boolean unreadable;

        VanillaJar(Path path) {
            this.path = path;
        }

        synchronized Optional<byte[]> classBytes(String internalName) {
            if (unreadable) {
                return Optional.empty();
            }
            try {
                if (jar == null) {
                    jar = new JarFile(path.toFile());
                }
                ZipEntry entry = jar.getEntry(internalName + ".class");
                if (entry == null) {
                    return Optional.empty();
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    return Optional.of(input.readAllBytes());
                }
            } catch (IOException e) {
                unreadable = true;
                throw new UncheckedIOException("Failed to read the vanilla game jar " + path, e);
            }
        }
    }
}
