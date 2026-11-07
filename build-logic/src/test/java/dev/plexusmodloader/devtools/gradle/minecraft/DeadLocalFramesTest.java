package dev.plexusmodloader.devtools.gradle.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

class DeadLocalFramesTest {

    private static final String CLASS = "dev/plexusmodloader/devtools/gradle/minecraft/PrunedSample";

    @Test
    void deadIntBecomesTopAndLiveLocalsStay() throws Exception {
        byte[] pruned = DeadLocalFrames.prune(sample());

        // pick(int a): dead = a * 2 (slot 1, last read at once), x = dead + 1 (slot 2), a branch, return x.
        assertEquals(List.of(List.of(Opcodes.INTEGER, Opcodes.TOP, Opcodes.INTEGER)), frames(pruned, "pick"));
    }

    @Test
    void deadLongBecomesTwoTopSlots() throws Exception {
        byte[] pruned = DeadLocalFrames.prune(sample());

        // wide(int a): w = a (long, slots 1 and 2, dead after l2i), x = (int) w (slot 3).
        assertEquals(
                List.of(List.of(Opcodes.INTEGER, Opcodes.TOP, Opcodes.TOP, Opcodes.INTEGER)), frames(pruned, "wide"));
    }

    @Test
    void thisAndParametersStayEvenWhenNothingReadsThemAfterTheFrame() throws Exception {
        byte[] pruned = DeadLocalFrames.prune(sample());

        // keep(int a): the frame before the return holds this and a, although only x is read after it.
        assertEquals(List.of(List.of(CLASS, Opcodes.INTEGER, Opcodes.INTEGER)), frames(pruned, "keep"));
    }

    @Test
    void localReadByAnExceptionHandlerStaysLive() throws Exception {
        byte[] pruned = DeadLocalFrames.prune(sample());

        // guard(int a): v = a (slot 1) is read in the handler, so the handler frame keeps it.
        assertEquals(List.of(List.of(Opcodes.INTEGER, Opcodes.INTEGER)), frames(pruned, "guard"));
    }

    @Test
    void prunedClassStillVerifiesAndRuns() throws Exception {
        byte[] pruned = DeadLocalFrames.prune(sample());

        Class<?> type = MethodHandles.lookup().defineClass(pruned);
        Object instance = type.getDeclaredConstructor().newInstance();
        assertEquals(4, call(type, null, "pick", 1));
        assertEquals(1, call(type, null, "pick", 0));
        assertEquals(5, call(type, null, "wide", 5));
        assertEquals(6, call(type, instance, "keep", 5));
        assertEquals(0, call(type, null, "guard", 0));
    }

    @Test
    void classWithoutDeadLocalsIsReturnedAsItIs() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/plexusmodloader/Plain", null, "java/lang/Object", null);
        writer.visitEnd();
        byte[] plain = writer.toByteArray();

        assertSame(plain, DeadLocalFrames.prune(plain));
    }

    private static Object call(Class<?> type, Object target, String name, int argument) throws Exception {
        Method method = type.getDeclaredMethod(name, int.class);
        return method.invoke(target, argument);
    }

    /** The expanded locals of every frame of {@code method}, in order. */
    private static List<List<Object>> frames(byte[] bytes, String method) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        List<List<Object>> frames = new ArrayList<>();
        for (MethodNode candidate : node.methods) {
            if (candidate.name.equals(method)) {
                for (AbstractInsnNode insn : candidate.instructions) {
                    if (insn instanceof FrameNode frame) {
                        frames.add(new ArrayList<>(frame.local));
                    }
                }
            }
        }
        return frames;
    }

    private static byte[] sample() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CLASS, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // static int pick(int a) { int dead = a * 2; int x = dead + 1; if (a > 0) { x = x + 1; } return x; }
        MethodVisitor pick = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(I)I", null, null);
        pick.visitCode();
        pick.visitVarInsn(Opcodes.ILOAD, 0);
        pick.visitInsn(Opcodes.ICONST_2);
        pick.visitInsn(Opcodes.IMUL);
        pick.visitVarInsn(Opcodes.ISTORE, 1);
        pick.visitVarInsn(Opcodes.ILOAD, 1);
        pick.visitInsn(Opcodes.ICONST_1);
        pick.visitInsn(Opcodes.IADD);
        pick.visitVarInsn(Opcodes.ISTORE, 2);
        Label end = new Label();
        pick.visitVarInsn(Opcodes.ILOAD, 0);
        pick.visitJumpInsn(Opcodes.IFLE, end);
        pick.visitVarInsn(Opcodes.ILOAD, 2);
        pick.visitInsn(Opcodes.ICONST_1);
        pick.visitInsn(Opcodes.IADD);
        pick.visitVarInsn(Opcodes.ISTORE, 2);
        pick.visitLabel(end);
        pick.visitVarInsn(Opcodes.ILOAD, 2);
        pick.visitInsn(Opcodes.IRETURN);
        pick.visitMaxs(0, 0);
        pick.visitEnd();

        // static int wide(int a) { long w = a; int x = (int) w; if (a > 0) { x = x + 0; } return x; }
        MethodVisitor wide = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "wide", "(I)I", null, null);
        wide.visitCode();
        wide.visitVarInsn(Opcodes.ILOAD, 0);
        wide.visitInsn(Opcodes.I2L);
        wide.visitVarInsn(Opcodes.LSTORE, 1);
        wide.visitVarInsn(Opcodes.LLOAD, 1);
        wide.visitInsn(Opcodes.L2I);
        wide.visitVarInsn(Opcodes.ISTORE, 3);
        Label wideEnd = new Label();
        wide.visitVarInsn(Opcodes.ILOAD, 0);
        wide.visitJumpInsn(Opcodes.IFLE, wideEnd);
        wide.visitIincInsn(3, 0);
        wide.visitLabel(wideEnd);
        wide.visitVarInsn(Opcodes.ILOAD, 3);
        wide.visitInsn(Opcodes.IRETURN);
        wide.visitMaxs(0, 0);
        wide.visitEnd();

        // int keep(int a) { int x = a; if (a > 0) { x = x + 1; } return x; }: this and a are never read after the frame.
        MethodVisitor keep = writer.visitMethod(Opcodes.ACC_PUBLIC, "keep", "(I)I", null, null);
        keep.visitCode();
        keep.visitVarInsn(Opcodes.ILOAD, 1);
        keep.visitVarInsn(Opcodes.ISTORE, 2);
        Label keepEnd = new Label();
        keep.visitVarInsn(Opcodes.ILOAD, 1);
        keep.visitJumpInsn(Opcodes.IFLE, keepEnd);
        keep.visitIincInsn(2, 1);
        keep.visitLabel(keepEnd);
        keep.visitVarInsn(Opcodes.ILOAD, 2);
        keep.visitInsn(Opcodes.IRETURN);
        keep.visitMaxs(0, 0);
        keep.visitEnd();

        // static int guard(int a) { int v = a; try { return 1 / a; } catch (Throwable t) { return v; } }
        MethodVisitor guard = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guard", "(I)I", null, null);
        guard.visitCode();
        Label start = new Label();
        Label stop = new Label();
        Label handler = new Label();
        guard.visitTryCatchBlock(start, stop, handler, "java/lang/Throwable");
        guard.visitVarInsn(Opcodes.ILOAD, 0);
        guard.visitVarInsn(Opcodes.ISTORE, 1);
        guard.visitLabel(start);
        guard.visitInsn(Opcodes.ICONST_1);
        guard.visitVarInsn(Opcodes.ILOAD, 0);
        guard.visitInsn(Opcodes.IDIV);
        guard.visitLabel(stop);
        guard.visitInsn(Opcodes.IRETURN);
        guard.visitLabel(handler);
        guard.visitInsn(Opcodes.POP);
        guard.visitVarInsn(Opcodes.ILOAD, 1);
        guard.visitInsn(Opcodes.IRETURN);
        guard.visitMaxs(0, 0);
        guard.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
