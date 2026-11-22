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
 * Forge on NeoForge, F-N6 (ADR-0051): Forge's {@code Mod.EventBusSubscriber.Bus} enum gets its {@code FORGE} constant's
 * bus group in its static initializer from Forge's {@code Bindings.getForgeBus()}. Forge's {@code Bindings} finds its
 * provider through Forge's launch ({@code FMLLoader.moduleLayerManager}), which does not run when NeoForge hosts the
 * game, so the first use of the enum (every {@code @Mod.EventBusSubscriber} Forge registers) fails. Forge's provider
 * gives {@code BusGroup.DEFAULT} ({@code ForgeBindings}). This patch of Forge's {@code javafmllanguage} jar, as the host
 * defines it among the loader libraries, makes that one call go to the host's {@code ForgeEventBusHooks.forgeBus()},
 * which gives the same group. The call keeps its length (both are three bytes), so the class's stack map frames stay as
 * they are; nothing else of Forge's class changes, and Forge's {@code Bindings} is not initialized.
 *
 * <p>Thread: stateless.
 */
final class ForgeEventBusPatch {

    static final String CLASS_FILE = "net/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus.class";
    static final String PACKAGE = "net.minecraftforge.fml.common";
    static final String HOOKS = "dev/plexusmodloader/compat/forge/fml/ForgeEventBusHooks";

    private static final String BINDINGS = "net/minecraftforge/fml/Bindings";
    private static final String GET_FORGE_BUS = "getForgeBus";
    private static final String SUPPLIER = "()Ljava/util/function/Supplier;";
    private static final String HOOK = "forgeBus";

    private ForgeEventBusPatch() {}

    /** How many calls of {@code Bindings.getForgeBus()} the static initializer of Forge's {@code Bus} {@code bytes} has. */
    static int calls(byte[] bytes) {
        int[] count = new int[1];
        new ClassReader(bytes).accept(redirecting(null, count), 0);
        return count[0];
    }

    /**
     * How many such calls Forge's {@code javafmllanguage} jar's {@code Bus} has; 0 when the jar has no such class.
     *
     * @throws IOException when the jar cannot be read
     */
    static int calls(Path javafmllanguage) throws IOException {
        try (JarFile jar = new JarFile(javafmllanguage.toFile())) {
            JarEntry entry = jar.getJarEntry(CLASS_FILE);
            if (entry == null) {
                return 0;
            }
            try (InputStream input = jar.getInputStream(entry)) {
                return calls(input.readAllBytes());
            } catch (IllegalArgumentException e) {
                throw new IOException(CLASS_FILE + " in " + javafmllanguage.getFileName() + ": " + e, e);
            }
        }
    }

    /**
     * Forge's {@code Bus} class {@code bytes} with its one call of {@code Bindings.getForgeBus()} redirected to the host's
     * hook.
     *
     * @throws IllegalStateException when the class does not have exactly one such call (another Forge release)
     */
    static byte[] apply(byte[] bytes) {
        int found = calls(bytes);
        if (found != 1) {
            throw new IllegalStateException(
                    "PLEXUS-COMPAT-229 Forge's Mod.EventBusSubscriber.Bus has " + found
                            + " call(s) of Bindings.getForgeBus() in its static initializer, not the 1 Plexus Mod Loader checked");
        }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(redirecting(writer, new int[1]), 0);
        return writer.toByteArray();
    }

    /** {@code reference}, reading Forge's {@code Bus} through {@link #apply} when it is Forge's module that has it. */
    static ModuleReference patching(ModuleReference reference) {
        if (!reference.descriptor().packages().contains(PACKAGE)) {
            return reference;
        }
        return PatchedClassReference.of(reference, CLASS_FILE, ForgeEventBusPatch::apply);
    }

    private static ClassVisitor redirecting(ClassVisitor next, int[] count) {
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
                                && owner.equals(BINDINGS)
                                && member.equals(GET_FORGE_BUS)
                                && type.equals(SUPPLIER)) {
                            count[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, HOOK, SUPPLIER, false);
                        } else {
                            super.visitMethodInsn(opcode, owner, member, type, isInterface);
                        }
                    }
                };
            }
        };
    }
}
