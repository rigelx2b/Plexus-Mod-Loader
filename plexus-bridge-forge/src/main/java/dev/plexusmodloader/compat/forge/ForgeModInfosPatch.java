package dev.plexusmodloader.compat.forge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Forge on NeoForge (ADR-0051 24): Forge's {@code ModList.getMods()} returns its static {@code SORTED_LIST}, which
 * {@code ModList}'s static initializer fills from Forge's loading mod list. Forge's launch fills that list, and it does
 * not run on NeoForge; the host gives it an empty state ({@code ForgeModList.prepare}), so {@code getMods()} would answer
 * an empty list while Forge mods run.
 *
 * <p>This patch of Forge's {@code fmlcore} jar, as the host defines it among the loader libraries, makes the one read of
 * {@code SORTED_LIST} in {@code getMods()} a call of the host's {@code ForgeModListHooks.mods()}, which answers the mod
 * infos of the Forge mods' containers, as Forge's {@code ModList} holds them ({@code setLoadedMods}). The instruction
 * keeps its length (three bytes) and its stack effect, so the method's frames stay as they are; nothing else of Forge's
 * class changes.
 *
 * <p>Thread: stateless.
 */
final class ForgeModInfosPatch {

    static final String PACKAGE = "net.minecraftforge.fml";
    static final String CLASS_FILE = "net/minecraftforge/fml/ModList.class";
    static final String OWNER = "net/minecraftforge/fml/ModList";
    static final String METHOD = "getMods";
    static final String METHOD_DESCRIPTOR = "()Ljava/util/List;";
    static final String FIELD = "SORTED_LIST";
    static final String FIELD_DESCRIPTOR = "Ljava/util/List;";
    static final String HOOKS = "dev/plexusmodloader/compat/forge/fml/ForgeModListHooks";
    static final String HOOK = "mods";

    private ForgeModInfosPatch() {}

    /** How many reads of {@code SORTED_LIST} the {@code getMods()} method of the class {@code bytes} has. */
    static int reads(byte[] bytes) {
        int[] count = new int[1];
        new ClassReader(bytes).accept(redirecting(null, count), 0);
        return count[0];
    }

    /**
     * How many reads of {@code SORTED_LIST} {@code ModList.getMods()} in Forge's {@code fmlcore} jar has (0 when the jar
     * has no {@code ModList}).
     *
     * @throws IOException when the jar or the class cannot be read
     */
    static int reads(Path fmlcore) throws IOException {
        try (JarFile jar = new JarFile(fmlcore.toFile())) {
            JarEntry entry = jar.getJarEntry(CLASS_FILE);
            if (entry == null) {
                return 0;
            }
            try (InputStream input = jar.getInputStream(entry)) {
                return reads(input.readAllBytes());
            } catch (IllegalArgumentException e) {
                throw new IOException(CLASS_FILE + " in " + fmlcore.getFileName() + ": " + e, e);
            }
        }
    }

    /**
     * The class {@code bytes} with the read of {@code SORTED_LIST} in {@code getMods()} going to the host's hook.
     *
     * @throws IllegalStateException when the method does not have exactly one such read (another Forge release)
     */
    static byte[] apply(byte[] bytes) {
        int found = reads(bytes);
        if (found != 1) {
            throw new IllegalStateException("PLEXUS-COMPAT-229 Forge's " + CLASS_FILE + " has " + found + " read(s) of "
                    + FIELD + " in " + METHOD + METHOD_DESCRIPTOR + ", not the 1 Plexus Mod Loader checked");
        }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(redirecting(writer, new int[1]), 0);
        return writer.toByteArray();
    }

    /** {@code reference}, reading Forge's {@code ModList} through {@link #apply} when it is Forge's module of it. */
    static ModuleReference patching(ModuleReference reference) {
        if (!reference.descriptor().packages().contains(PACKAGE)) {
            return reference;
        }
        return PatchedClassReference.of(reference, CLASS_FILE, ForgeModInfosPatch::apply);
    }

    private static ClassVisitor redirecting(ClassVisitor next, int[] count) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            private String className;

            @Override
            public void visit(
                    int version, int access, String name, String signature, String superName, String[] interfaces) {
                className = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!OWNER.equals(className) || !name.equals(METHOD) || !descriptor.equals(METHOD_DESCRIPTOR)) {
                    return method;
                }
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String type) {
                        if (opcode == Opcodes.GETSTATIC
                                && owner.equals(OWNER)
                                && field.equals(FIELD)
                                && type.equals(FIELD_DESCRIPTOR)) {
                            count[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, HOOK, METHOD_DESCRIPTOR, false);
                        } else {
                            super.visitFieldInsn(opcode, owner, field, type);
                        }
                    }
                };
            }
        };
    }
}
