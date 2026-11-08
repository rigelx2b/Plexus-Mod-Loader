package dev.plexusmodloader.devtools.gradle.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

/** The obfuscated jar's frames are kept, with {@code this} and the parameters live in every one. */
class ParameterFramesTest {

    private static final String OWNER = "net/minecraft/world/inventory/EnchantmentMenu";
    private static final String DESCRIPTOR =
            "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V";

    /** A class with one method {@code name} of {@code descriptor}, whose body {@code body} writes. */
    private static byte[] method(String name, String descriptor, int access, Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(access, name, descriptor, null, null);
        method.visitCode();
        body.accept(method);
        method.visitMaxs(4, 8);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<FrameNode> frames(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        MethodNode method = node.methods.get(0);
        List<FrameNode> frames = new ArrayList<>();
        method.instructions.forEach(insn -> {
            if (insn instanceof FrameNode frame) {
                frames.add(frame);
            }
        });
        return frames;
    }

    /**
     * The obfuscator's frame in {@code EnchantmentMenu.lambda$slotsChanged$0} after the bookshelf loop: {@code full_frame
     * [EnchantmentMenu, ItemStack, top, top, int]}. It stays a full frame (Mixin then reads every local from it), with
     * the two parameters live.
     */
    @Test
    void aFullFrameWithDeadParametersStaysFullWithThemLive() {
        byte[] input = method("run", DESCRIPTOR, Opcodes.ACC_PRIVATE, body -> {
            Label after = new Label();
            body.visitInsn(Opcodes.ICONST_0);
            body.visitVarInsn(Opcodes.ISTORE, 4);
            body.visitVarInsn(Opcodes.ILOAD, 4);
            body.visitJumpInsn(Opcodes.IFEQ, after);
            body.visitLabel(after);
            body.visitFrame(
                    Opcodes.F_FULL,
                    5,
                    new Object[] {OWNER, "net/minecraft/world/item/ItemStack", Opcodes.TOP, Opcodes.TOP, Opcodes.INTEGER},
                    0,
                    new Object[0]);
            body.visitInsn(Opcodes.RETURN);
        });

        FrameNode frame = frames(ParameterFrames.keepParameters(input)).get(0);

        assertEquals(Opcodes.F_FULL, frame.type);
        assertEquals(
                List.of(
                        OWNER,
                        "net/minecraft/world/item/ItemStack",
                        "net/minecraft/world/level/Level",
                        "net/minecraft/core/BlockPos",
                        Opcodes.INTEGER),
                frame.local);
    }

    /** A {@code chop} that takes off parameters can no longer say the frame: it is written full. */
    @Test
    void aChopIntoTheParametersBecomesAFullFrame() {
        byte[] input = method("run", "(II)V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, body -> {
            Label after = new Label();
            body.visitVarInsn(Opcodes.ILOAD, 0);
            body.visitJumpInsn(Opcodes.IFEQ, after);
            body.visitLabel(after);
            body.visitFrame(Opcodes.F_CHOP, 1, null, 0, null);
            body.visitInsn(Opcodes.RETURN);
        });

        FrameNode frame = frames(ParameterFrames.keepParameters(input)).get(0);

        assertEquals(Opcodes.F_FULL, frame.type);
        assertEquals(List.of(Opcodes.INTEGER, Opcodes.INTEGER), frame.local);
    }

    /** Frames that already hold the parameters, as javac writes them, are left as they are: the same bytes. */
    @Test
    void framesThatHoldTheParametersAreKeptAsTheyAre() {
        byte[] input = method("run", "(I)V", Opcodes.ACC_PUBLIC, body -> {
            Label loop = new Label();
            Label done = new Label();
            body.visitInsn(Opcodes.ICONST_0);
            body.visitVarInsn(Opcodes.ISTORE, 2);
            body.visitLabel(loop);
            body.visitFrame(Opcodes.F_APPEND, 1, new Object[] {Opcodes.INTEGER}, 0, null);
            body.visitVarInsn(Opcodes.ILOAD, 2);
            body.visitVarInsn(Opcodes.ILOAD, 1);
            body.visitJumpInsn(Opcodes.IF_ICMPGE, done);
            body.visitIincInsn(2, 1);
            body.visitJumpInsn(Opcodes.GOTO, loop);
            body.visitLabel(done);
            body.visitFrame(Opcodes.F_CHOP, 1, null, 0, null);
            body.visitInsn(Opcodes.RETURN);
        });

        assertSame(input, ParameterFrames.keepParameters(input));
    }

    /** A dead {@code long} parameter is two {@code TOP} slots in the obfuscator's frame; it becomes one {@code LONG}. */
    @Test
    void aDeadLongParameterBecomesLive() {
        byte[] input = method("run", "(JI)V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, body -> {
            Label after = new Label();
            body.visitVarInsn(Opcodes.ILOAD, 2);
            body.visitJumpInsn(Opcodes.IFEQ, after);
            body.visitLabel(after);
            body.visitFrame(Opcodes.F_FULL, 3, new Object[] {Opcodes.TOP, Opcodes.TOP, Opcodes.INTEGER}, 0, null);
            body.visitInsn(Opcodes.RETURN);
        });

        FrameNode frame = frames(ParameterFrames.keepParameters(input)).get(0);

        assertEquals(Opcodes.F_FULL, frame.type);
        assertEquals(List.of(Opcodes.LONG, Opcodes.INTEGER), frame.local);
    }

    /** In a constructor {@code this} may be uninitialised in a frame, so it is not set; the parameters are. */
    @Test
    void aConstructorKeepsItsThisAsTheObfuscatorWroteIt() {
        byte[] input = method("<init>", "(I)V", Opcodes.ACC_PUBLIC, body -> {
            Label after = new Label();
            body.visitVarInsn(Opcodes.ALOAD, 0);
            body.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            body.visitInsn(Opcodes.ICONST_0);
            body.visitJumpInsn(Opcodes.IFEQ, after);
            body.visitLabel(after);
            body.visitFrame(Opcodes.F_FULL, 0, new Object[0], 0, null);
            body.visitInsn(Opcodes.RETURN);
        });

        FrameNode frame = frames(ParameterFrames.keepParameters(input)).get(0);

        assertEquals(Opcodes.F_FULL, frame.type);
        assertEquals(List.of(Opcodes.TOP, Opcodes.INTEGER), frame.local);
    }
}
