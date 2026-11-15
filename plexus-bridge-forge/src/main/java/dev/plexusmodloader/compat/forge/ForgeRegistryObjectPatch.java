package dev.plexusmodloader.compat.forge;

import java.lang.module.ModuleReference;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Forge on NeoForge, F-N4 (ADR-0051): Forge's {@code RegistryObject} asks a registry {@code isIntrusive()} when it is made
 * before its entry is registered (every {@code DeferredRegister.register} does). That method is one MinecraftForge's patch
 * adds to {@code MappedRegistry} ({@code unregisteredIntrusiveHolders != null}); NeoForge's game has the field but not the
 * method. This patch of Forge's {@code universal} jar, as the host defines it among the loader libraries, makes that one
 * call go to the host's {@code ForgeRegistryHooks.isIntrusive(Object)}, which reads the same field. It changes nothing else
 * of Forge's class: the call keeps its length (both are three bytes), so the class's stack map frames stay as they are.
 *
 * <p>Thread: stateless.
 */
final class ForgeRegistryObjectPatch {

    static final String CLASS_FILE = "net/minecraftforge/registries/RegistryObject.class";
    static final String PACKAGE = "net.minecraftforge.registries";
    static final String HOOKS = "dev/plexusmodloader/compat/forge/fml/ForgeRegistryHooks";

    private static final String MAPPED_REGISTRY = "net/minecraft/core/MappedRegistry";
    private static final String IS_INTRUSIVE = "isIntrusive";

    private ForgeRegistryObjectPatch() {}

    /** How many calls of {@code MappedRegistry.isIntrusive()} Forge's {@code RegistryObject} class {@code bytes} has. */
    static int calls(byte[] bytes) {
        int[] count = new int[1];
        new ClassReader(bytes).accept(redirecting(null, count), 0);
        return count[0];
    }

    /**
     * Forge's {@code RegistryObject} class {@code bytes} with its one call of {@code MappedRegistry.isIntrusive()}
     * redirected to the host's hook.
     *
     * @throws IllegalStateException when the class does not have exactly one such call (another Forge release)
     */
    static byte[] apply(byte[] bytes) {
        int found = calls(bytes);
        if (found != 1) {
            throw new IllegalStateException("PLEXUS-COMPAT-229 Forge's RegistryObject has " + found
                    + " call(s) of MappedRegistry.isIntrusive(), not the 1 Plexus Mod Loader checked");
        }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(redirecting(writer, new int[1]), 0);
        return writer.toByteArray();
    }

    /** {@code reference}, reading Forge's {@code RegistryObject} through {@link #apply} when it is Forge's module. */
    static ModuleReference patching(ModuleReference reference) {
        if (!reference.descriptor().packages().contains(PACKAGE)) {
            return reference;
        }
        return PatchedClassReference.of(reference, CLASS_FILE, ForgeRegistryObjectPatch::apply);
    }

    private static ClassVisitor redirecting(ClassVisitor next, int[] count) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitMethodInsn(
                            int opcode, String owner, String member, String type, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(MAPPED_REGISTRY)
                                && member.equals(IS_INTRUSIVE)
                                && type.equals("()Z")) {
                            count[0]++;
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC, HOOKS, IS_INTRUSIVE, "(Ljava/lang/Object;)Z", false);
                        } else {
                            super.visitMethodInsn(opcode, owner, member, type, isInterface);
                        }
                    }
                };
            }
        };
    }
}
