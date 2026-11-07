package dev.plexusmodloader.installer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Turns the dead locals of a class's stack map frames into {@code TOP}, as the obfuscator leaves them.
 *
 * <p>A frame computed by ASM lists every local assigned on the way, like javac's. The obfuscated game jar lists only
 * the locals that are still read after the frame. Mixin reads the locals at an injection point from these frames
 * (the game jars have no usable variable names to Mixin), and an implicit {@code @ModifyVariable} such as Puzzles Lib's
 * on {@code LivingEntity.baseTick} needs exactly one local of its type there; with a dead local of the same type in the
 * frame it finds two and the injection fails. Dead means no instruction on any path from the frame, exception handlers
 * included, reads the local before writing it again.
 *
 * <p>{@code this} and the parameters stay in every frame: a hook that a transformation rule inserts before a {@code
 * return} loads them there, and the obfuscator's own frame at the last {@code return} may hold nothing (ADR-0019
 * finding, remapper revision 2). The frames stay valid: a dead local is never read before it is written, so the
 * verifier needs no type for it, and every state that reaches the frame fits {@code TOP}.
 *
 * <p>The same class is in {@code build-logic} for the remapper of the build; change both together and raise {@code MojangJarRemapper.REVISION}.
 */
final class DeadLocalFrames {

    private DeadLocalFrames() {}

    /** The class with its dead locals turned into {@code TOP} in every frame; the same array if nothing changed. */
    static byte[] prune(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            changed |= prune(method);
        }
        if (!changed) {
            return classBytes;
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean prune(MethodNode method) {
        InsnList list = method.instructions;
        if (list.size() == 0) {
            return false;
        }
        AbstractInsnNode[] insns = list.toArray();
        boolean hasFrame = false;
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof FrameNode) {
                hasFrame = true;
                break;
            }
        }
        if (!hasFrame) {
            return false;
        }
        BitSet[] liveIn = liveIn(method, insns);
        int parameterSlots = parameterSlots(method);
        boolean changed = false;
        for (int index = 0; index < insns.length; index++) {
            if (insns[index] instanceof FrameNode frame && frame.type == Opcodes.F_NEW && frame.local != null) {
                changed |= prune(frame, liveIn[index], parameterSlots);
            }
        }
        return changed;
    }

    private static boolean prune(FrameNode frame, BitSet live, int parameterSlots) {
        List<Object> pruned = new ArrayList<>(frame.local.size());
        boolean changed = false;
        int slot = 0;
        for (Object entry : frame.local) {
            boolean wide = entry == Opcodes.LONG || entry == Opcodes.DOUBLE;
            boolean keep = slot < parameterSlots || live.get(slot) || (wide && live.get(slot + 1));
            if (keep) {
                pruned.add(entry);
            } else {
                pruned.add(Opcodes.TOP);
                if (wide) {
                    pruned.add(Opcodes.TOP);
                }
                changed |= entry != Opcodes.TOP;
            }
            slot += wide ? 2 : 1;
        }
        // The obfuscator's frames end at the last live local; so do these.
        while (!pruned.isEmpty() && pruned.get(pruned.size() - 1) == Opcodes.TOP) {
            pruned.remove(pruned.size() - 1);
        }
        if (changed) {
            frame.local = pruned;
        }
        return changed;
    }

    private static int parameterSlots(MethodNode method) {
        int slots = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            slots += argument.getSize();
        }
        return slots;
    }

    /** For each instruction, the locals read on some path from it before being written. */
    private static BitSet[] liveIn(MethodNode method, AbstractInsnNode[] insns) {
        InsnList list = method.instructions;
        int count = insns.length;
        List<int[]> successors = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            successors.add(successorsOf(list, insns, index));
        }
        // Every instruction of a protected range can go to the handler.
        List<List<Integer>> handlers = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            handlers.add(new ArrayList<>());
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            int start = list.indexOf(block.start);
            int end = list.indexOf(block.end);
            int handler = list.indexOf(block.handler);
            for (int index = start; index < end; index++) {
                handlers.get(index).add(handler);
            }
        }
        List<List<Integer>> predecessors = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            predecessors.add(new ArrayList<>());
        }
        for (int index = 0; index < count; index++) {
            for (int next : successors.get(index)) {
                predecessors.get(next).add(index);
            }
            for (int handler : handlers.get(index)) {
                predecessors.get(handler).add(index);
            }
        }

        BitSet[] liveIn = new BitSet[count];
        for (int index = 0; index < count; index++) {
            liveIn[index] = new BitSet();
        }
        Deque<Integer> work = new ArrayDeque<>();
        boolean[] queued = new boolean[count];
        for (int index = count - 1; index >= 0; index--) {
            work.add(index);
            queued[index] = true;
        }
        while (!work.isEmpty()) {
            int index = work.poll();
            queued[index] = false;
            BitSet out = new BitSet();
            for (int next : successors.get(index)) {
                out.or(liveIn[next]);
            }
            for (int handler : handlers.get(index)) {
                out.or(liveIn[handler]);
            }
            BitSet in = transfer(insns[index], out);
            if (!in.equals(liveIn[index])) {
                liveIn[index] = in;
                for (int before : predecessors.get(index)) {
                    if (!queued[before]) {
                        work.add(before);
                        queued[before] = true;
                    }
                }
            }
        }
        return liveIn;
    }

    /** {@code in = use + (out - def)} for one instruction. */
    private static BitSet transfer(AbstractInsnNode insn, BitSet out) {
        BitSet in = (BitSet) out.clone();
        if (insn instanceof VarInsnNode var) {
            int opcode = var.getOpcode();
            boolean wide = opcode == Opcodes.LLOAD
                    || opcode == Opcodes.DLOAD
                    || opcode == Opcodes.LSTORE
                    || opcode == Opcodes.DSTORE;
            if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                in.clear(var.var);
                if (wide) {
                    in.clear(var.var + 1);
                }
            } else if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
                in.set(var.var);
                if (wide) {
                    in.set(var.var + 1);
                }
            }
        } else if (insn instanceof IincInsnNode iinc) {
            in.set(iinc.var);
        }
        return in;
    }

    private static int[] successorsOf(InsnList list, AbstractInsnNode[] insns, int index) {
        AbstractInsnNode insn = insns[index];
        int opcode = insn.getOpcode();
        boolean hasNext = index + 1 < insns.length;
        if (insn instanceof JumpInsnNode jump) {
            int target = list.indexOf(jump.label);
            if (opcode == Opcodes.GOTO || !hasNext) {
                return new int[] {target};
            }
            return new int[] {target, index + 1};
        }
        if (insn instanceof TableSwitchInsnNode table) {
            return switchTargets(list, table.dflt, table.labels);
        }
        if (insn instanceof LookupSwitchInsnNode lookup) {
            return switchTargets(list, lookup.dflt, lookup.labels);
        }
        if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW || !hasNext) {
            return new int[0];
        }
        return new int[] {index + 1};
    }

    private static int[] switchTargets(InsnList list, LabelNode defaultLabel, List<LabelNode> labels) {
        int[] targets = new int[labels.size() + 1];
        targets[0] = list.indexOf(defaultLabel);
        for (int i = 0; i < labels.size(); i++) {
            targets[i + 1] = list.indexOf(labels.get(i));
        }
        return targets;
    }
}
