package dev.plexusmodloader.compat.forge;

import java.lang.module.ModuleReference;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Forge on NeoForge, F-N4 (ADR-0051): Forge's {@code ForgeRegistries} fills its {@code IForgeRegistry} fields in its
 * static initializer: it calls its {@code init()}, whose {@code GameData.init()} makes Forge's own registries (a {@code
 * NamespacedWrapper} for each vanilla registry, which only MinecraftForge's patched game uses as its registry), and then
 * {@code active(key)} for each field, which reads those Forge registries. On NeoForge's game they would be empty and apart
 * from the registries blocks and items are in. This patch of Forge's {@code universal} jar, as the host defines it among
 * the loader libraries, makes the initializer's {@code init()} call and every {@code active(ResourceKey)} call go to the
 * host's {@code ForgeRegistryHooks}: {@code init()} to one that does nothing, {@code active} to one that hands out a view of
 * the game's registry under that key (returned as an {@code Object}, as the host is not compiled against Forge's {@code
 * IForgeRegistry}, and cast to it by a {@code checkcast} the patch adds after each such call). The calls keep their length
 * (three bytes); each added cast moves the initializer's later code by three bytes, and ASM writes the initializer's stack
 * map frames at their new offsets (the frames themselves do not change, as a cast to the field's type leaves the same
 * type on the stack). Forge's {@code init()} and {@code active} methods stay in the class, uncalled. The fields of Forge's own
 * registries ({@code DeferredRegister.RegistryHolder}s) are left as Forge makes them.
 *
 * <p>Thread: stateless.
 */
final class ForgeRegistriesPatch {

    static final String CLASS_FILE = "net/minecraftforge/registries/ForgeRegistries.class";

    private static final String OWNER = "net/minecraftforge/registries/ForgeRegistries";
    private static final String INIT = "init";
    private static final String ACTIVE = "active";
    private static final String ACTIVE_TYPE =
            "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraftforge/registries/IForgeRegistry;";
    private static final String HOOK_INIT = "initForgeRegistries";
    private static final String HOOK_ACTIVE = "gameRegistry";
    private static final String HOOK_ACTIVE_TYPE = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String STATIC_INITIALIZER = "<clinit>";

    /** Forge's registry interface, whose members the host's view answers. */
    static final String FORGE_REGISTRY = "net/minecraftforge/registries/IForgeRegistry";

    /**
     * The members of Forge's {@code IForgeRegistry} the host's view of a game registry answers ({@code ForgeRegistryView}),
     * as name and descriptor; the runtime scope check allows the same names ({@code ForgeOnNeoForgeScope}).
     */
    static final List<String> VIEW_METHODS = List.of(
            "getRegistryKey()Lnet/minecraft/resources/ResourceKey;",
            "getRegistryName()Lnet/minecraft/resources/Identifier;",
            "containsKey(Lnet/minecraft/resources/Identifier;)Z",
            "containsValue(Ljava/lang/Object;)Z",
            "isEmpty()Z",
            "getValue(Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;",
            "getKey(Ljava/lang/Object;)Lnet/minecraft/resources/Identifier;",
            "getDefaultKey()Lnet/minecraft/resources/Identifier;",
            "getResourceKey(Ljava/lang/Object;)Ljava/util/Optional;",
            "getKeys()Ljava/util/Set;",
            "getValues()Ljava/util/Collection;",
            "getEntries()Ljava/util/Set;",
            "getHolder(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;",
            "getHolder(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;",
            "getHolder(Ljava/lang/Object;)Ljava/util/Optional;");

    private ForgeRegistriesPatch() {}

    /** The calls this patch redirects in Forge's {@code ForgeRegistries} class: where and how many. */
    record Calls(int init, int active, int elsewhere) {

        /** Whether the class is the one this patch was checked against: one {@code init()}, some {@code active}, all in the initializer. */
        boolean fit() {
            return init == 1 && active > 0 && elsewhere == 0;
        }

        @Override
        public String toString() {
            return init + " init() and " + active + " active(ResourceKey) call(s) in <clinit>, " + elsewhere
                    + " elsewhere";
        }
    }

    /** The calls of Forge's {@code ForgeRegistries} class {@code bytes} this patch redirects. */
    static Calls calls(byte[] bytes) {
        int[] count = new int[3];
        new ClassReader(bytes).accept(redirecting(null, count), 0);
        return new Calls(count[0], count[1], count[2]);
    }

    /**
     * Forge's {@code ForgeRegistries} class {@code bytes} with its initializer's calls of {@code init()} and {@code
     * active(ResourceKey)} redirected to the host's hooks.
     *
     * @throws IllegalStateException when the class does not {@link Calls#fit} (another Forge release)
     */
    static byte[] apply(byte[] bytes) {
        Calls found = calls(bytes);
        if (!found.fit()) {
            throw new IllegalStateException("PLEXUS-COMPAT-229 Forge's ForgeRegistries has " + found
                    + ", not the shape Plexus Mod Loader checked");
        }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(redirecting(writer, new int[3]), 0);
        return writer.toByteArray();
    }

    /** {@code reference}, reading Forge's {@code ForgeRegistries} through {@link #apply} when it is Forge's module. */
    static ModuleReference patching(ModuleReference reference) {
        if (!reference.descriptor().packages().contains(ForgeRegistryObjectPatch.PACKAGE)) {
            return reference;
        }
        return PatchedClassReference.of(reference, CLASS_FILE, ForgeRegistriesPatch::apply);
    }

    private static ClassVisitor redirecting(ClassVisitor next, int[] count) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean initializer = name.equals(STATIC_INITIALIZER);
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitMethodInsn(
                            int opcode, String owner, String member, String type, boolean isInterface) {
                        boolean init = opcode == Opcodes.INVOKESTATIC
                                && owner.equals(OWNER)
                                && member.equals(INIT)
                                && type.equals("()V");
                        boolean active = opcode == Opcodes.INVOKESTATIC
                                && owner.equals(OWNER)
                                && member.equals(ACTIVE)
                                && type.equals(ACTIVE_TYPE);
                        if ((init || active) && !initializer) {
                            count[2]++;
                            super.visitMethodInsn(opcode, owner, member, type, isInterface);
                        } else if (init) {
                            count[0]++;
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC, ForgeRegistryObjectPatch.HOOKS, HOOK_INIT, "()V", false);
                        } else if (active) {
                            count[1]++;
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    ForgeRegistryObjectPatch.HOOKS,
                                    HOOK_ACTIVE,
                                    HOOK_ACTIVE_TYPE,
                                    false);
                            super.visitTypeInsn(Opcodes.CHECKCAST, FORGE_REGISTRY);
                        } else {
                            super.visitMethodInsn(opcode, owner, member, type, isInterface);
                        }
                    }
                };
            }
        };
    }
}
