package dev.plexusmodloader.compat.fabric.mixin;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An {@code @Inject} into {@code Level.setBlock(BlockPos, BlockState, int, int)} before its {@code
 * updatePOIOnBlockStateChange} call, on MinecraftForge (26.2-65.1.5 and 26.3-66.1.0, checked with {@code javap}).
 * Vanilla's {@code setBlock} sets the state in the chunk ({@code LevelChunk.setBlockState}, which returns the old state)
 * and, when the state is still the one it set, marks the block dirty, sends it, updates neighbours and shapes, and
 * finally calls {@code updatePOIOnBlockStateChange(pos, old, current)}. Forge moves everything after {@code
 * setBlockState} into {@code markAndNotifyBlock(BlockPos, LevelChunk, BlockState old, BlockState state, int flags, int
 * maxUpdateDepth)}, which {@code setBlock} calls with its chunk, the old state and its own parameters (later, for each
 * block, while Forge captures block snapshots for an item placement); the body is vanilla's, with the same {@code
 * current = getBlockState(pos)} read first. Lithium's {@code LevelMixin.updateHopperOnUpdateSuppression} runs there to
 * let hoppers next to the block drop their cached inventories when the update was suppressed.
 *
 * <p>The bridge ({@link #bridgeMethod}) is an {@code @Inject} at the same call in {@code markAndNotifyBlock}. It calls
 * the handler with {@code setBlock}'s parameters (the position, the new state, the flags and the update depth, all
 * {@code markAndNotifyBlock}'s), {@code null} for the {@code CallbackInfoReturnable} (the handler never reads it), and
 * each local the handler captures from vanilla's {@code setBlock}: the chunk, the new state's block, the old state
 * ({@code BlockState} ordinal 1) and the current one (ordinal 2, captured in {@code markAndNotifyBlock} as its third
 * {@code BlockState}). Only a handler of that shape is bridged, and only when both games' bytecode is the one described
 * here ({@link #fits}).
 *
 * <p>Thread: any; no state.
 */
final class HostBlockChangeNotify {

    static final String REPLACED = "Lnet/minecraft/world/level/Level;updatePOIOnBlockStateChange("
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/block/state/BlockState;)V";
    /** A marker for the replaced call: Forge makes {@link #REPLACED} in this method. */
    static final String REPLACEMENT = "markAndNotifyBlock(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/block/state/BlockState;II)V";

    private static final String TARGET_CLASS = "net/minecraft/world/level/Level";
    private static final String TARGET_METHOD = "setBlock";
    private static final String TARGET_DESCRIPTOR =
            "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z";
    private static final String HOST_METHOD = "markAndNotifyBlock";
    private static final String HOST_DESCRIPTOR = REPLACEMENT.substring(HOST_METHOD.length());
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
    private static final ClassDesc LEVEL = ClassDesc.ofInternalName(TARGET_CLASS);
    private static final ClassDesc BLOCK_POS = ClassDesc.of("net.minecraft.core.BlockPos");
    private static final ClassDesc BLOCK_STATE = ClassDesc.of("net.minecraft.world.level.block.state.BlockState");
    private static final ClassDesc BLOCK = ClassDesc.of("net.minecraft.world.level.block.Block");
    private static final ClassDesc LEVEL_CHUNK = ClassDesc.of("net.minecraft.world.level.chunk.LevelChunk");
    private static final ClassDesc CALLBACK_INFO =
            ClassDesc.of("org.spongepowered.asm.mixin.injection.callback.CallbackInfo");
    private static final ClassDesc CALLBACK_INFO_RETURNABLE =
            ClassDesc.of("org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable");
    private static final String GET_CHUNK_AT = "getChunkAt";
    private static final String SET_BLOCK_STATE = "setBlockState";
    private static final String GET_BLOCK_STATE = "getBlockState";

    /** A local the handler captures from vanilla's {@code setBlock}, and where the bridge takes it from. */
    enum Capture {
        CHUNK,
        BLOCK,
        OLD_STATE,
        CURRENT_STATE
    }

    private HostBlockChangeNotify() {}

    /**
     * Whether {@code handler}, a {@code type} injection ({@code injector}) into {@code targetClass.method} at {@code
     * point}, which the patch removed there, is the injection this class bridges on {@code vanilla} and {@code patched}.
     */
    static boolean fits(
            MethodModel handler,
            String type,
            Annotation injector,
            String targetClass,
            String method,
            String point,
            ClassModel vanilla,
            ClassModel patched) {
        if (!type.equals(INJECT)
                || !targetClass.equals(TARGET_CLASS)
                || !method.equals(TARGET_METHOD)
                || !point.equals(REPLACED)
                || handler.flags().has(AccessFlag.STATIC)
                || !plainInjector(injector)
                || captures(handler).isEmpty()) {
            return false;
        }
        // The handler never reads its CallbackInfoReturnable (slot 5: this, pos, state, flags, depth before it), which
        // the bridge does not have.
        int callbackSlot = 5;
        if (instructions(handler).stream()
                .anyMatch(instruction -> instruction instanceof LoadInstruction load && load.slot() == callbackSlot)) {
            return false;
        }
        Optional<MethodModel> vanillaSetBlock =
                HostPatchAdjustments.methods(vanilla, TARGET_METHOD, TARGET_DESCRIPTOR).stream()
                        .findFirst();
        Optional<MethodModel> patchedSetBlock =
                HostPatchAdjustments.methods(patched, TARGET_METHOD, TARGET_DESCRIPTOR).stream()
                        .findFirst();
        Optional<MethodModel> notify = HostPatchAdjustments.methods(patched, HOST_METHOD, HOST_DESCRIPTOR).stream()
                .findFirst();
        return vanillaSetBlock.isPresent()
                && patchedSetBlock.isPresent()
                && notify.isPresent()
                && vanillaShape(vanillaSetBlock.get())
                && patchedShape(patchedSetBlock.get())
                && notifyShape(notify.get());
    }

    /** Whether a call {@code replaced} that the host replaced with {@code replacement} is the one this class bridges. */
    static boolean covers(String replaced, String replacement) {
        return replaced.equals(REPLACED) && replacement.equals(REPLACEMENT);
    }

    /**
     * The locals {@code handler} captures after {@code setBlock}'s parameters and its {@code CallbackInfoReturnable},
     * each with exactly one MixinExtras {@code @Local}; empty when the handler has another shape.
     */
    static Optional<List<Capture>> captures(MethodModel handler) {
        MethodTypeDesc type = handler.methodTypeSymbol();
        if (!type.returnType().equals(ConstantDescs.CD_void)
                || type.parameterCount() < 5
                || !type.parameterType(0).equals(BLOCK_POS)
                || !type.parameterType(1).equals(BLOCK_STATE)
                || !type.parameterType(2).equals(ConstantDescs.CD_int)
                || !type.parameterType(3).equals(ConstantDescs.CD_int)
                || !type.parameterType(4).equals(CALLBACK_INFO_RETURNABLE)
                || handler.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                        .isPresent()) {
            return Optional.empty();
        }
        List<List<Annotation>> annotations = handler.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                .map(attribute -> attribute.parameterAnnotations())
                .orElse(List.of());
        List<Capture> captures = new ArrayList<>();
        for (int index = 0; index < type.parameterCount(); index++) {
            List<Annotation> own = index < annotations.size() ? annotations.get(index) : List.of();
            if (index < 5) {
                if (!own.isEmpty()) {
                    return Optional.empty();
                }
                continue;
            }
            if (own.size() != 1 || !own.getFirst().className().equalsString(LOCAL)) {
                return Optional.empty();
            }
            Optional<Integer> ordinal = Optional.empty();
            for (AnnotationElement element : own.getFirst().elements()) {
                if (element.name().equalsString("ordinal") && element.value() instanceof AnnotationValue.OfInt value) {
                    ordinal = Optional.of(value.intValue());
                } else {
                    return Optional.empty();
                }
            }
            ClassDesc parameter = type.parameterType(index);
            int number = ordinal.orElse(-1);
            if (parameter.equals(LEVEL_CHUNK) && number <= 0) {
                captures.add(Capture.CHUNK);
            } else if (parameter.equals(BLOCK) && number <= 0) {
                captures.add(Capture.BLOCK);
            } else if (parameter.equals(BLOCK_STATE) && number == 1) {
                captures.add(Capture.OLD_STATE);
            } else if (parameter.equals(BLOCK_STATE) && number == 2) {
                captures.add(Capture.CURRENT_STATE);
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(List.copyOf(captures));
    }

    /** Whether the injector has only {@code method} and one {@code @At(value = "INVOKE", target = ...)}. */
    private static boolean plainInjector(Annotation injector) {
        for (AnnotationElement element : injector.elements()) {
            String name = element.name().stringValue();
            if (name.equals("method") || name.equals("cancellable") || name.equals("require")) {
                continue;
            }
            if (!name.equals("at")
                    || !(element.value() instanceof AnnotationValue.OfArray array)
                    || array.values().size() != 1) {
                return false;
            }
            if (!(array.values().getFirst() instanceof AnnotationValue.OfAnnotation at)
                    || !at.annotation().className().equalsString(AT)) {
                return false;
            }
            for (AnnotationElement atElement : at.annotation().elements()) {
                String atName = atElement.name().stringValue();
                if (!atName.equals("value") && !atName.equals("target") && !atName.equals("remap")) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Vanilla's {@code setBlock}: the chunk is the local stored from {@code getChunkAt}, the block the one from {@code
     * BlockState.getBlock()}, and the call is {@code updatePOIOnBlockStateChange(pos, <setBlockState's result>,
     * <getBlockState's result>)}, each stored in a local once.
     */
    private static boolean vanillaShape(MethodModel setBlock) {
        List<Instruction> code = instructions(setBlock);
        int chunk = storedFrom(code, LEVEL, GET_CHUNK_AT);
        int old = storedFrom(code, LEVEL_CHUNK, SET_BLOCK_STATE);
        int current = storedFrom(code, LEVEL, GET_BLOCK_STATE);
        // The chunk, the block, the old and the current state are its only reference locals: the old state is the
        // second BlockState there (after the parameter), the current one the third.
        long stores = code.stream()
                .filter(instruction ->
                        instruction instanceof StoreInstruction store && store.typeKind() == TypeKind.REFERENCE)
                .count();
        return chunk >= 0
                && old >= 0
                && current >= 0
                && old < current
                && stores == 4
                && storedFrom(code, BLOCK_STATE, "getBlock") >= 0
                && callLoads(code, REPLACED, List.of(0, 1, old, current));
    }

    /**
     * Forge's {@code setBlock}: it calls {@code markAndNotifyBlock(pos, chunk, old, state, flags, maxUpdateDepth)} with
     * the local from {@code getChunkAt}, the local from {@code setBlockState} and its own parameters.
     */
    private static boolean patchedShape(MethodModel setBlock) {
        List<Instruction> code = instructions(setBlock);
        int chunk = storedFrom(code, LEVEL, GET_CHUNK_AT);
        int old = storedFrom(code, LEVEL_CHUNK, SET_BLOCK_STATE);
        return chunk >= 0
                && old >= 0
                && callLoads(code, "L" + TARGET_CLASS + ";" + REPLACEMENT, List.of(0, 1, chunk, old, 2, 3, 4))
                && !HostPatchAdjustments.contains(setBlock, REPLACED);
    }

    /**
     * Forge's {@code markAndNotifyBlock}: it stores {@code getBlockState(pos)} once (its third {@code BlockState}
     * after the two parameters) and the new state's block, and calls {@code updatePOIOnBlockStateChange(pos, old,
     * <that local>)}.
     */
    private static boolean notifyShape(MethodModel notify) {
        List<Instruction> code = instructions(notify);
        int current = storedFrom(code, LEVEL, GET_BLOCK_STATE);
        long stores = code.stream()
                .filter(instruction ->
                        instruction instanceof StoreInstruction store && store.typeKind() == TypeKind.REFERENCE)
                .count();
        return current >= 0
                && stores == 2
                && storedFrom(code, BLOCK_STATE, "getBlock") >= 0
                && callLoads(code, REPLACED, List.of(0, 1, 3, current));
    }

    /**
     * The local that the result of a call {@code owner.name} is stored in right after it, when exactly one such call is
     * stored and that local is written nowhere else; otherwise -1.
     */
    private static int storedFrom(List<Instruction> code, ClassDesc owner, String name) {
        int found = -1;
        for (int index = 0; index + 1 < code.size(); index++) {
            if (code.get(index) instanceof InvokeInstruction call
                    && call.owner().asSymbol().equals(owner)
                    && call.name().equalsString(name)
                    && code.get(index + 1) instanceof StoreInstruction store) {
                if (found >= 0) {
                    return -1;
                }
                found = store.slot();
            }
        }
        if (found < 0) {
            return -1;
        }
        int slot = found;
        long writes = code.stream()
                .filter(instruction -> instruction instanceof StoreInstruction store && store.slot() == slot)
                .count();
        return writes == 1 ? slot : -1;
    }

    /** Whether the one call {@code target} in {@code code} is made right after loading exactly {@code slots}, in order. */
    private static boolean callLoads(List<Instruction> code, String target, List<Integer> slots) {
        int found = -1;
        for (int index = 0; index < code.size(); index++) {
            if (code.get(index) instanceof InvokeInstruction call
                    && target.equals("L" + call.owner().asInternalName() + ";"
                            + call.name().stringValue() + call.type().stringValue())) {
                if (found >= 0) {
                    return false;
                }
                found = index;
            }
        }
        if (found < slots.size()) {
            return false;
        }
        for (int index = 0; index < slots.size(); index++) {
            if (!(code.get(found - slots.size() + index) instanceof LoadInstruction load)
                    || load.slot() != slots.get(index)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Adds the bridge {@code plexus$hostCall$<handler>}: an {@code @Inject} into {@code markAndNotifyBlock} at {@code
     * updatePOIOnBlockStateChange} that calls the handler as vanilla's {@code setBlock} would.
     */
    static void bridgeMethod(ClassBuilder builder, ClassModel mixin, MethodModel handler) {
        List<Capture> captures = captures(handler).orElseThrow();
        String name = handler.methodName().stringValue();
        ClassDesc owner = mixin.thisClass().asSymbol();
        boolean isInterface = mixin.flags().has(AccessFlag.INTERFACE);
        Annotation inject = Annotation.of(
                ClassDesc.ofDescriptor(INJECT),
                AnnotationElement.of("method", AnnotationValue.ofArray(AnnotationValue.ofString(REPLACEMENT))),
                AnnotationElement.of(
                        "at",
                        AnnotationValue.ofArray(AnnotationValue.ofAnnotation(Annotation.of(
                                ClassDesc.ofDescriptor(AT),
                                AnnotationElement.ofString("value", "INVOKE"),
                                AnnotationElement.ofString("target", REPLACED))))));
        // markAndNotifyBlock's parameters, the CallbackInfo, and the current state (BlockState ordinal 2 there).
        MethodTypeDesc bridgeType = MethodTypeDesc.of(
                ConstantDescs.CD_void,
                BLOCK_POS,
                LEVEL_CHUNK,
                BLOCK_STATE,
                BLOCK_STATE,
                ConstantDescs.CD_int,
                ConstantDescs.CD_int,
                CALLBACK_INFO,
                BLOCK_STATE);
        List<List<Annotation>> parameterAnnotations = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            parameterAnnotations.add(List.of());
        }
        parameterAnnotations.add(
                List.of(Annotation.of(ClassDesc.ofDescriptor(LOCAL), AnnotationElement.ofInt("ordinal", 2))));
        builder.withMethod(
                HostReplacedCalls.PREFIX + name, bridgeType, handler.flags().flagsMask(), method -> {
                    method.with(RuntimeVisibleAnnotationsAttribute.of(inject));
                    method.with(RuntimeInvisibleParameterAnnotationsAttribute.of(parameterAnnotations));
                    method.withCode(code -> {
                        code.aload(0);
                        code.aload(1);
                        code.aload(4);
                        code.iload(5);
                        code.iload(6);
                        code.aconst_null();
                        for (Capture capture : captures) {
                            switch (capture) {
                                case CHUNK -> code.aload(2);
                                case BLOCK -> {
                                    code.aload(4);
                                    code.invokevirtual(BLOCK_STATE, "getBlock", MethodTypeDesc.of(BLOCK));
                                }
                                case OLD_STATE -> code.aload(3);
                                case CURRENT_STATE -> code.aload(8);
                            }
                        }
                        code.invokespecial(owner, name, handler.methodTypeSymbol(), isInterface);
                        code.return_();
                    });
                });
    }

    private static List<Instruction> instructions(MethodModel method) {
        List<Instruction> code = new ArrayList<>();
        method.code().ifPresent(body -> {
            for (CodeElement element : body) {
                if (element instanceof Instruction instruction) {
                    code.add(instruction);
                }
            }
        });
        return code;
    }
}
