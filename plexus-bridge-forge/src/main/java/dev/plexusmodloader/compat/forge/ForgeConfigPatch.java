package dev.plexusmodloader.compat.forge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Forge on NeoForge, F-N6 (ADR-0051): Forge's config classes in its {@code fmlcore} jar read two parts of Forge's launch
 * state in their static initializers, which do not exist when NeoForge hosts the game:
 *
 * <ul>
 *   <li>{@code IConfigEvent} gets the factories of its config events from Forge's {@code
 *       Bindings.getConfigConfiguration()}, which finds its provider through Forge's launch ({@code
 *       FMLLoader.moduleLayerManager}). Forge's provider ({@code ForgeBindings}) makes {@code ModConfigEvent.Loading},
 *       {@code Reloading} and {@code Unloading}; the host's {@code ForgeConfigHooks.configConfiguration()} makes the same.
 *   <li>{@code ConfigFileTypeHandler} reads the folder of default config files from Forge's {@code FMLConfig}, which
 *       only Forge's launch loads (from {@code config/fml.toml}, the file NeoForge's FML keeps its own settings in). The
 *       host's {@code ForgeConfigHooks.defaultConfigPath(Object)} gives NeoForge's setting instead.
 * </ul>
 *
 * <p>This patch of Forge's {@code fmlcore} jar, as the host defines it among the loader libraries, makes each of those
 * two calls go to the host's hook. Each call keeps its length (three bytes) and its stack effect, so the classes' stack
 * map frames stay as they are; nothing else of Forge's classes changes, and neither Forge's {@code Bindings} nor its
 * {@code FMLConfig} is initialized.
 *
 * <p>Thread: stateless.
 */
final class ForgeConfigPatch {

    static final String PACKAGE = "net.minecraftforge.fml.config";
    static final String HOOKS = "dev/plexusmodloader/compat/forge/fml/ForgeConfigHooks";

    /** One static call in a class's static initializer and the host hook it goes to. */
    record Redirect(
            String classFile, String owner, String name, String descriptor, String hook, String hookDescriptor) {}

    /** The redirected calls, one per class file. */
    static final List<Redirect> REDIRECTS = List.of(
            new Redirect(
                    "net/minecraftforge/fml/config/IConfigEvent.class",
                    "net/minecraftforge/fml/Bindings",
                    "getConfigConfiguration",
                    "()Ljava/util/function/Supplier;",
                    "configConfiguration",
                    "()Ljava/util/function/Supplier;"),
            new Redirect(
                    "net/minecraftforge/fml/config/ConfigFileTypeHandler.class",
                    "net/minecraftforge/fml/loading/FMLConfig",
                    "getConfigValue",
                    "(Lnet/minecraftforge/fml/loading/FMLConfig$ConfigValue;)Ljava/lang/String;",
                    "defaultConfigPath",
                    "(Ljava/lang/Object;)Ljava/lang/String;"));

    private ForgeConfigPatch() {}

    /** How many calls {@code redirect} names the static initializer of the class {@code bytes} has. */
    static int calls(byte[] bytes, Redirect redirect) {
        int[] count = new int[1];
        new ClassReader(bytes).accept(redirecting(null, redirect, count), 0);
        return count[0];
    }

    /**
     * For each redirect, how many such calls Forge's {@code fmlcore} jar has (0 for a class it does not have), in the
     * order of {@link #REDIRECTS}.
     *
     * @throws IOException when the jar or one of the classes cannot be read
     */
    static Map<Redirect, Integer> calls(Path fmlcore) throws IOException {
        Map<Redirect, Integer> calls = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(fmlcore.toFile())) {
            for (Redirect redirect : REDIRECTS) {
                JarEntry entry = jar.getJarEntry(redirect.classFile());
                if (entry == null) {
                    calls.put(redirect, 0);
                    continue;
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    calls.put(redirect, calls(input.readAllBytes(), redirect));
                } catch (IllegalArgumentException e) {
                    throw new IOException(redirect.classFile() + " in " + fmlcore.getFileName() + ": " + e, e);
                }
            }
        }
        return calls;
    }

    /**
     * The class {@code bytes} with its one call {@code redirect} names going to the host's hook.
     *
     * @throws IllegalStateException when the class does not have exactly one such call (another Forge release)
     */
    static byte[] apply(byte[] bytes, Redirect redirect) {
        int found = calls(bytes, redirect);
        if (found != 1) {
            throw new IllegalStateException("PLEXUS-COMPAT-229 Forge's " + redirect.classFile() + " has " + found
                    + " call(s) of " + redirect.owner() + "." + redirect.name() + redirect.descriptor()
                    + " in its static initializer, not the 1 Plexus Mod Loader checked");
        }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(redirecting(writer, redirect, new int[1]), 0);
        return writer.toByteArray();
    }

    /** {@code reference}, reading Forge's two config classes through {@link #apply} when it is Forge's module of them. */
    static ModuleReference patching(ModuleReference reference) {
        if (!reference.descriptor().packages().contains(PACKAGE)) {
            return reference;
        }
        ModuleReference patched = reference;
        for (Redirect redirect : REDIRECTS) {
            patched = PatchedClassReference.of(patched, redirect.classFile(), bytes -> apply(bytes, redirect));
        }
        return patched;
    }

    private static ClassVisitor redirecting(ClassVisitor next, Redirect redirect, int[] count) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("<clinit>")) {
                    return method;
                }
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitMethodInsn(
                            int opcode, String owner, String member, String type, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && owner.equals(redirect.owner())
                                && member.equals(redirect.name())
                                && type.equals(redirect.descriptor())) {
                            count[0]++;
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC, HOOKS, redirect.hook(), redirect.hookDescriptor(), false);
                        } else {
                            super.visitMethodInsn(opcode, owner, member, type, isInterface);
                        }
                    }
                };
            }
        };
    }
}
