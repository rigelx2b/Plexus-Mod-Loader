package dev.plexusmodloader.compat.fabric.mixin;

import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A {@code @WrapOperation} of vanilla's soil moisture read in {@code CropBlock.getGrowthSpeed(Block, BlockGetter,
 * BlockPos)} on MinecraftForge (26.2-65.1.5 and 26.3-66.1.0, checked with {@code javap}). Vanilla reads {@code
 * BlockState.getValueOrElse(FarmlandBlock.MOISTURE, 0)} on the block under the crop and counts the soil as watered when
 * the value is above 0. Forge asks {@code BlockState.isFertile(BlockGetter, BlockPos)} at the same place; its default
 * ({@code IForgeBlock.isFertile}) is true exactly for a {@code FarmlandBlock} whose {@code MOISTURE} is above 0, the
 * vanilla answer, and a Forge mod's block may answer for itself. Balm's {@code FabricCropBlockMixin} wraps the vanilla
 * read: when the moisture is not above 0 and the soil is one of Balm's {@code CustomFarmBlock}s that sustains the plant
 * and is fertile, it returns 1.
 *
 * <p>The bridge ({@link #bridgeMethod}) wraps Forge's {@code isFertile} call: it makes it once, then calls the handler
 * with the vanilla arguments ({@code MOISTURE}, {@code 0}) and a {@link PrecomputedCall} that returns {@code 1} when
 * Forge said fertile and {@code 0} otherwise, and returns whether the handler's value is above 0, as vanilla tests it.
 * The handler thus decides as written with Forge's answer where it read the moisture. Only a handler that makes the read
 * once with its own arguments ({@link HostReplacedCalls#callsOnceWithOwnArguments}) and uses the value it gets only by
 * returning it or by comparing its {@code int} value with 0 ({@link #readsOnlyWhetherAboveZero}) is bridged: for such a
 * handler, 0 or 1 in place of the moisture (never negative) leads to the same result above 0 or not.
 *
 * <p>Thread: any; no state.
 */
final class HostFertileSoil {

    static final String REPLACED = "Lnet/minecraft/world/level/block/state/BlockState;getValueOrElse("
            + "Lnet/minecraft/world/level/block/state/properties/Property;Ljava/lang/Comparable;)"
            + "Ljava/lang/Comparable;";
    static final String REPLACEMENT = "Lnet/minecraft/world/level/block/state/BlockState;isFertile("
            + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z";

    private static final String TARGET_CLASS = "net/minecraft/world/level/block/CropBlock";
    private static final String TARGET_METHOD = "getGrowthSpeed";
    private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final ClassDesc OPERATION =
            ClassDesc.ofDescriptor("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;");
    private static final ClassDesc BLOCK_STATE = ClassDesc.of("net.minecraft.world.level.block.state.BlockState");
    private static final ClassDesc PROPERTY = ClassDesc.of("net.minecraft.world.level.block.state.properties.Property");
    private static final ClassDesc INTEGER_PROPERTY =
            ClassDesc.of("net.minecraft.world.level.block.state.properties.IntegerProperty");
    private static final ClassDesc FARMLAND = ClassDesc.of("net.minecraft.world.level.block.FarmlandBlock");
    private static final ClassDesc BLOCK_GETTER = ClassDesc.of("net.minecraft.world.level.BlockGetter");
    private static final ClassDesc BLOCK_POS = ClassDesc.of("net.minecraft.core.BlockPos");
    private static final ClassDesc COMPARABLE = ClassDesc.of("java.lang.Comparable");
    private static final ClassDesc PRECOMPUTED_CALL = ClassDesc.of(PrecomputedCall.class.getName());
    private static final Set<Opcode> COMPARISONS_WITH_ZERO = Set.of(Opcode.IFGT, Opcode.IFLE, Opcode.IFEQ, Opcode.IFNE);

    private HostFertileSoil() {}

    /**
     * Whether {@code handler}, a {@code type} injection into {@code targetClass.method} at {@code replaced}, which the
     * host replaced with {@code replacement}, is the wrap this class bridges.
     */
    static boolean fits(
            MethodModel handler, String type, String targetClass, String method, String replaced, String replacement) {
        if (!type.equals(WRAP_OPERATION)
                || !targetClass.equals(TARGET_CLASS)
                || !method.equals(TARGET_METHOD)
                || !covers(replaced, replacement)) {
            return false;
        }
        MethodTypeDesc handlerType = handler.methodTypeSymbol();
        return handlerType.parameterCount() >= 4
                && handlerType.parameterType(0).equals(BLOCK_STATE)
                && handlerType.parameterType(1).equals(PROPERTY)
                && handlerType.parameterType(2).equals(COMPARABLE)
                && handlerType.parameterType(3).equals(OPERATION)
                && handlerType.returnType().equals(COMPARABLE)
                && !HostReplacedCalls.annotatedBefore(handler, 4)
                && HostReplacedCalls.callsOnceWithOwnArguments(handler, 3)
                && readsOnlyWhetherAboveZero(handler);
    }

    /** Whether a call {@code replaced} that the host replaced with {@code replacement} is the one this class bridges. */
    static boolean covers(String replaced, String replacement) {
        return replaced.equals(REPLACED) && replacement.equals(REPLACEMENT);
    }

    /**
     * Whether {@code handler} uses the value its {@code Operation} returns only by returning it as it is, or by testing
     * its {@code int} value against 0 ({@code checkcast Integer}, {@code intValue()}, then {@code ifgt}, {@code ifle},
     * {@code ifeq} or {@code ifne}). The value is either kept on the stack to be returned, or stored in one local that
     * is never written again.
     */
    static boolean readsOnlyWhetherAboveZero(MethodModel handler) {
        List<Instruction> code = new ArrayList<>();
        handler.code().ifPresent(body -> {
            for (CodeElement element : body) {
                if (element instanceof Instruction instruction) {
                    code.add(instruction);
                }
            }
        });
        int call = -1;
        for (int index = 0; index < code.size(); index++) {
            if (code.get(index) instanceof InvokeInstruction invoke
                    && invoke.owner().asSymbol().equals(OPERATION)
                    && invoke.name().equalsString("call")) {
                call = index;
            }
        }
        if (call < 0) {
            return false;
        }
        int next = call + 1;
        if (next < code.size()
                && code.get(next) instanceof TypeCheckInstruction cast
                && cast.opcode() == Opcode.CHECKCAST) {
            next++;
        }
        if (next >= code.size()) {
            return false;
        }
        if (code.get(next) instanceof ReturnInstruction result) {
            return result.opcode() == Opcode.ARETURN;
        }
        if (!(code.get(next) instanceof StoreInstruction store) || store.typeKind() != TypeKind.REFERENCE) {
            return false;
        }
        int slot = store.slot();
        for (int index = 0; index < code.size(); index++) {
            Instruction instruction = code.get(index);
            if (index != next && instruction instanceof StoreInstruction other && other.slot() == slot) {
                return false;
            }
            if (!(instruction instanceof LoadInstruction load) || load.slot() != slot) {
                continue;
            }
            if (index + 1 >= code.size()) {
                return false;
            }
            if (code.get(index + 1) instanceof ReturnInstruction result && result.opcode() == Opcode.ARETURN) {
                continue;
            }
            boolean comparedWithZero = index + 3 < code.size()
                    && code.get(index + 1) instanceof TypeCheckInstruction cast
                    && cast.opcode() == Opcode.CHECKCAST
                    && cast.type().asSymbol().equals(ConstantDescs.CD_Integer)
                    && code.get(index + 2) instanceof InvokeInstruction value
                    && value.owner().asSymbol().equals(ConstantDescs.CD_Integer)
                    && value.name().equalsString("intValue")
                    && code.get(index + 3) instanceof BranchInstruction branch
                    && COMPARISONS_WITH_ZERO.contains(branch.opcode());
            if (!comparedWithZero) {
                return false;
            }
        }
        return true;
    }

    /**
     * Adds the bridge {@code plexus$hostCall$<handler>(BlockState, BlockGetter, BlockPos, Operation, ...)} carrying
     * {@code wrapOperation} (the handler's, which the handler loses) retargeted at Forge's {@code isFertile}; the
     * handler's parameters after its {@code Operation} (the target method's) pass through with their annotations.
     */
    static void bridgeMethod(ClassBuilder builder, ClassModel mixin, MethodModel handler, Annotation wrapOperation) {
        String name = handler.methodName().stringValue();
        ClassDesc owner = mixin.thisClass().asSymbol();
        boolean isInterface = mixin.flags().has(AccessFlag.INTERFACE);
        int flags = handler.flags().flagsMask();
        boolean isStatic = (flags & ClassFile.ACC_STATIC) != 0;
        MethodTypeDesc handlerType = handler.methodTypeSymbol();
        List<ClassDesc> trailing = handlerType.parameterList().subList(4, handlerType.parameterCount());
        List<ClassDesc> parameters = new ArrayList<>(List.of(BLOCK_STATE, BLOCK_GETTER, BLOCK_POS, OPERATION));
        parameters.addAll(trailing);
        MethodTypeDesc bridgeType = MethodTypeDesc.of(ConstantDescs.CD_boolean, parameters);
        int[] slots = new int[parameters.size()];
        int next = isStatic ? 0 : 1;
        for (int index = 0; index < parameters.size(); index++) {
            slots[index] = next;
            next += TypeKind.from(parameters.get(index)).slotSize();
        }
        builder.withMethod(HostReplacedCalls.PREFIX + name, bridgeType, flags, method -> {
            method.with(RuntimeVisibleAnnotationsAttribute.of(
                    HostReplacedCalls.retargeted(wrapOperation, REPLACED, REPLACEMENT)));
            handler.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                    .ifPresent(
                            attribute -> method.with(RuntimeVisibleParameterAnnotationsAttribute.of(trailingAnnotations(
                                    attribute.parameterAnnotations(), parameters.size(), trailing.size()))));
            handler.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                    .ifPresent(attribute ->
                            method.with(RuntimeInvisibleParameterAnnotationsAttribute.of(trailingAnnotations(
                                    attribute.parameterAnnotations(), parameters.size(), trailing.size()))));
            method.withCode(code -> {
                // Forge's isFertile, once: the soil's state, the level and the position.
                code.aload(slots[3]);
                code.iconst_3();
                code.anewarray(ConstantDescs.CD_Object);
                for (int index = 0; index < 3; index++) {
                    code.dup();
                    code.loadConstant(index);
                    code.aload(slots[index]);
                    code.aastore();
                }
                code.invokeinterface(
                        OPERATION,
                        "call",
                        MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object.arrayType()));
                code.checkcast(ConstantDescs.CD_Boolean);
                code.invokevirtual(
                        ConstantDescs.CD_Boolean, "booleanValue", MethodTypeDesc.of(ConstantDescs.CD_boolean));
                int fertile = code.allocateLocal(TypeKind.INT);
                code.istore(fertile);
                // The handler: the soil's state, vanilla's MOISTURE and 0, Forge's answer as 1 or 0, the rest.
                if (!isStatic) {
                    code.aload(0);
                }
                code.aload(slots[0]);
                code.getstatic(FARMLAND, "MOISTURE", INTEGER_PROPERTY);
                code.iconst_0();
                code.invokestatic(
                        ConstantDescs.CD_Integer,
                        "valueOf",
                        MethodTypeDesc.of(ConstantDescs.CD_Integer, ConstantDescs.CD_int));
                code.new_(PRECOMPUTED_CALL);
                code.dup();
                code.iload(fertile);
                code.invokestatic(
                        ConstantDescs.CD_Integer,
                        "valueOf",
                        MethodTypeDesc.of(ConstantDescs.CD_Integer, ConstantDescs.CD_int));
                code.invokespecial(
                        PRECOMPUTED_CALL,
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
                for (int index = 0; index < trailing.size(); index++) {
                    int at = 4 + index;
                    code.loadLocal(TypeKind.from(parameters.get(at)), slots[at]);
                }
                if (isStatic) {
                    code.invokestatic(owner, name, handlerType, isInterface);
                } else {
                    code.invokespecial(owner, name, handlerType, isInterface);
                }
                // Vanilla's test of the value: (Integer) value > 0.
                code.checkcast(ConstantDescs.CD_Integer);
                code.invokevirtual(ConstantDescs.CD_Integer, "intValue", MethodTypeDesc.of(ConstantDescs.CD_int));
                code.ifThenElse(Opcode.IFGT, then -> then.iconst_1(), otherwise -> otherwise.iconst_0());
                code.ireturn();
            });
        });
    }

    /** Parameter annotations for the bridge: only the last {@code trailing} keep the handler's (from its fifth on). */
    private static List<List<Annotation>> trailingAnnotations(
            List<List<Annotation>> annotations, int count, int trailing) {
        List<List<Annotation>> placed = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            placed.add(List.of());
        }
        for (int index = 0; index < trailing; index++) {
            int from = 4 + index;
            if (from < annotations.size()) {
                placed.set(count - trailing + index, annotations.get(from));
            }
        }
        return placed;
    }
}
