package dev.plexusmodloader.internal.transform.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Makes {@code this} and the parameters live in every stack map frame of a method a rule gives a hook before its returns.
 *
 * <p>The obfuscator keeps only live locals in a frame: a parameter nothing reads any more is {@code TOP}, and at the
 * last {@code return} of a method a frame may hold no locals at all, so a hook that a rule inserts there could not load
 * {@code this} or a parameter (ADR-0019). The remapped game jar keeps the obfuscated jar's frames as they are (remapper
 * revision 5): Mixin 0.8.7 reads the locals at an injection point from the frames' kinds ({@code Locals.getLocalsAt}),
 * and the mods were made against those frames (QSL's implicit {@code @ModifyVariable} in {@code EnchantmentMenu},
 * ADR-0048; Cardinal Components' in {@code BlockInput.place} found no {@code BlockEntity} when every frame was full).
 * Only the methods a rule gives a hook before their returns get live parameters, when the rule applies, and that rule
 * applies after the other loaders' Mixin (ClassTransformExecutor#transformLate).
 *
 * <p>Every other local stays as the obfuscator wrote it, and so does the kind of each frame where it still fits. A frame
 * whose kind no longer fits because a parameter became live (a {@code chop} into the parameters, or a frame after a full
 * frame that changed) is written as a full frame. A parameter slot that holds anything but {@code TOP} keeps its type.
 * In a constructor, {@code this} is left as it is: before the call to the super constructor it is uninitialised. The
 * frames stay valid: a parameter slot is only ever written with a value of the parameter's type. A method whose frames
 * already hold its parameters (code compiled by {@code javac}, such as the game from 26.1 on) is not changed.
 */
final class ParameterFrames {

    private ParameterFrames() {}

    /**
     * Makes {@code this} and the parameters live in every frame of {@code method} of class {@code owner}.
     *
     * @return whether a frame changed
     */
    static boolean keepParameters(String owner, MethodNode method) {
        boolean constructor = method.name.equals("<init>");
        Object[] parameters = parameterSlots(owner, method, constructor);
        List<Object> previousOriginal = entries(parameters, constructor ? Opcodes.UNINITIALIZED_THIS : null);
        List<Object> previousKept = previousOriginal;
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof FrameNode frame)) {
                continue;
            }
            List<Object> original = locals(previousOriginal, frame);
            List<Object> kept = entries(withParameters(slots(original), parameters), null);
            // TOP that only pads up to a parameter left as it was (this of a constructor) says nothing.
            while (kept.size() > original.size() && kept.get(kept.size() - 1) == Opcodes.TOP) {
                kept.remove(kept.size() - 1);
            }
            if (!kept.equals(original) || !previousKept.equals(previousOriginal)) {
                changed |= rewrite(frame, previousKept, kept);
            }
            previousOriginal = original;
            previousKept = kept;
        }
        return changed;
    }

    /**
     * The types of {@code this} and the parameters by local slot; the second slot of a {@code long} or {@code double} is
     * {@code null}, and so is {@code this} of a constructor.
     */
    private static Object[] parameterSlots(String owner, MethodNode method, boolean constructor) {
        List<Object> slots = new ArrayList<>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            slots.add(constructor ? null : owner);
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            slots.add(frameType(argument));
            if (argument.getSize() == 2) {
                slots.add(null);
            }
        }
        return slots.toArray();
    }

    private static Object frameType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
            case Type.FLOAT -> Opcodes.FLOAT;
            case Type.LONG -> Opcodes.LONG;
            case Type.DOUBLE -> Opcodes.DOUBLE;
            case Type.ARRAY -> type.getDescriptor();
            default -> type.getInternalName();
        };
    }

    /** The frame's locals, as entries ({@code long} and {@code double} are one entry), from the frame before it. */
    private static List<Object> locals(List<Object> previous, FrameNode frame) {
        return switch (frame.type) {
            case Opcodes.F_FULL, Opcodes.F_NEW -> new ArrayList<>(frame.local);
            case Opcodes.F_APPEND -> {
                List<Object> locals = new ArrayList<>(previous);
                locals.addAll(frame.local);
                yield locals;
            }
            case Opcodes.F_CHOP -> new ArrayList<>(previous.subList(0, previous.size() - frame.local.size()));
            default -> previous;
        };
    }

    /** Entries to slots: a {@code long} or {@code double} takes two, the second {@code null}. */
    private static Object[] slots(List<Object> entries) {
        List<Object> slots = new ArrayList<>();
        for (Object entry : entries) {
            slots.add(entry);
            if (entry == Opcodes.LONG || entry == Opcodes.DOUBLE) {
                slots.add(null);
            }
        }
        return slots.toArray();
    }

    /** Slots to entries; {@code this} of a constructor, when it is {@code null} in {@code slots}, as {@code initial}. */
    private static List<Object> entries(Object[] slots, Object initial) {
        List<Object> entries = new ArrayList<>(slots.length);
        for (int slot = 0; slot < slots.length; slot++) {
            Object entry = slots[slot];
            if (entry == null && slot == 0 && initial != null) {
                entries.add(initial);
                continue;
            }
            if (entry == null) {
                continue; // the second slot of a long or double
            }
            entries.add(entry);
        }
        return entries;
    }

    /** {@code locals} with each parameter slot that is {@code TOP} or missing set to the parameter's type. */
    private static Object[] withParameters(Object[] locals, Object[] parameters) {
        Object[] result =
                locals.length >= parameters.length ? locals.clone() : Arrays.copyOf(locals, parameters.length);
        for (int slot = locals.length; slot < result.length; slot++) {
            result[slot] = Opcodes.TOP;
        }
        for (int slot = 0; slot < parameters.length; slot++) {
            Object type = parameters[slot];
            if (type == null || result[slot] != Opcodes.TOP) {
                continue;
            }
            boolean wide = type == Opcodes.LONG || type == Opcodes.DOUBLE;
            if (wide && result[slot + 1] != Opcodes.TOP) {
                continue;
            }
            result[slot] = type;
            if (wide) {
                result[slot + 1] = null;
            }
        }
        return result;
    }

    /**
     * Writes {@code kept} into {@code frame}: in the frame's own kind relative to {@code previous} when that still says
     * {@code kept}, else as a full frame.
     *
     * @return whether the frame changed
     */
    private static boolean rewrite(FrameNode frame, List<Object> previous, List<Object> kept) {
        boolean fits = switch (frame.type) {
            case Opcodes.F_SAME, Opcodes.F_SAME1 -> kept.equals(previous);
            case Opcodes.F_APPEND ->
                kept.size() == previous.size() + frame.local.size()
                        && kept.subList(0, previous.size()).equals(previous)
                        && kept.subList(previous.size(), kept.size()).equals(frame.local);
            case Opcodes.F_CHOP ->
                previous.size() - kept.size() == frame.local.size()
                        && previous.subList(0, kept.size()).equals(kept);
            default -> false;
        };
        if (fits) {
            return false;
        }
        if (frame.type == Opcodes.F_FULL && frame.local.equals(kept)) {
            return false;
        }
        List<Object> stack = switch (frame.type) {
            case Opcodes.F_FULL, Opcodes.F_SAME1 -> frame.stack;
            default -> List.of();
        };
        frame.type = Opcodes.F_FULL;
        frame.local = new ArrayList<>(kept);
        frame.stack = new ArrayList<>(stack);
        return true;
    }
}
