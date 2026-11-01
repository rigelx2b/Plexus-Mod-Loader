package dev.plexusmodloader.compat.fabric.mixin;

import dev.plexusmodloader.compat.fabric.mixin.HostReplacedCalls.Call;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A {@code @WrapOperation} of an instance call that the host's patch replaced, at the same place, with a static call
 * taking the receiver first, then arguments of its own, then the vanilla arguments, with the same result ({@link
 * dev.plexusmodloader.compat.bridge.InjectionPointReplacement.Placement#SAME_PLACE}): NeoForge 26.3.0.98's {@code
 * Gui.extractRenderState} calls {@code ClientHooks.extractScreen(screen, screenLayers, graphics, mouseX, mouseY,
 * partialTick)} where vanilla calls {@code screen.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY,
 * partialTick)}; NeoForge's helper draws its screen layers and posts its screen render events around that very call.
 * FancyMenu's {@code MixinFabricGameRenderer} wraps the vanilla call to draw before and after the screen.
 *
 * <p>The bridge ({@link #bridgeMethod}) wraps the host's call and calls the handler with the receiver, the vanilla
 * arguments and an {@link InsertedArguments}, which makes the host's call when the handler makes the wrapped one, with
 * the host's own arguments as the patched game passed them. The handler thus runs as written around the host's call,
 * which does what the vanilla call did and what the host added to it. Only a handler that makes the call once, as
 * given, is bridged ({@link HostReplacedCalls#callsOnceWithOwnArguments}), and only when the patched method makes the
 * host's call as a static one.
 *
 * <p>Thread: any; no state.
 */
final class HostStaticCalls {

    static final String PREFIX = "plexus$hostStaticCall$";

    private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final ClassDesc OPERATION =
            ClassDesc.ofDescriptor("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;");
    private static final ClassDesc INSERTED_ARGUMENTS = ClassDesc.of(InsertedArguments.class.getName());

    private HostStaticCalls() {}

    /**
     * Whether {@code replacement} takes the receiver of the instance call {@code replaced} first, at least one argument
     * of its own, then {@code replaced}'s arguments, and returns the same type, on another owner.
     */
    static boolean covers(String replaced, String replacement) {
        Optional<Call> vanilla = Call.parse(replaced);
        Optional<Call> host = Call.parse(replacement);
        if (vanilla.isEmpty()
                || host.isEmpty()
                || vanilla.get().owner().equals(host.get().owner())) {
            return false;
        }
        MethodTypeDesc vanillaType = vanilla.get().type();
        MethodTypeDesc hostType = host.get().type();
        int own = hostType.parameterCount() - 1 - vanillaType.parameterCount();
        return own > 0
                && hostType.returnType().equals(vanillaType.returnType())
                && hostType.parameterType(0)
                        .descriptorString()
                        .equals(vanilla.get().owner())
                && hostType.parameterList()
                        .subList(1 + own, hostType.parameterCount())
                        .equals(vanillaType.parameterList());
    }

    /**
     * Whether a {@code @WrapOperation} handler {@code handler} of {@code replaced} can run through the bridge at {@code
     * replacement}: the calls fit ({@link #covers}), {@code patched} (the patched target methods) make {@code
     * replacement} as a static call, and the handler takes the receiver, the vanilla arguments with their types, the
     * {@code Operation}, maybe the target method's parameters after it, and makes the wrapped call once, as given.
     */
    static boolean fits(
            MethodModel handler, String type, String replaced, String replacement, List<MethodModel> patched) {
        if (!type.equals(WRAP_OPERATION) || !covers(replaced, replacement) || !callsStatically(patched, replacement)) {
            return false;
        }
        Call vanilla = Call.parse(replaced).orElseThrow();
        MethodTypeDesc handlerType = handler.methodTypeSymbol();
        int arguments = vanilla.type().parameterCount();
        if (HostReplacedCalls.annotatedBefore(handler, arguments + 2)
                || handlerType.parameterCount() < arguments + 2
                || !handlerType.returnType().equals(vanilla.type().returnType())
                || !handlerType.parameterType(0).descriptorString().equals(vanilla.owner())
                || !handlerType.parameterType(arguments + 1).equals(OPERATION)) {
            return false;
        }
        for (int index = 0; index < arguments; index++) {
            if (!handlerType.parameterType(index + 1).equals(vanilla.type().parameterType(index))) {
                return false;
            }
        }
        return HostReplacedCalls.callsOnceWithOwnArguments(handler, arguments + 1);
    }

    private static boolean callsStatically(List<MethodModel> methods, String replacement) {
        Call host = Call.parse(replacement).orElseThrow();
        for (MethodModel method : methods) {
            Optional<Boolean> found = method.code().map(code -> {
                for (CodeElement element : code) {
                    if (element instanceof InvokeInstruction invoke
                            && invoke.opcode() == Opcode.INVOKESTATIC
                            && invoke.owner().asSymbol().descriptorString().equals(host.owner())
                            && invoke.name().equalsString(host.name())
                            && invoke.typeSymbol().equals(host.type())) {
                        return true;
                    }
                }
                return false;
            });
            if (found.orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds the bridge of {@code handler} to {@code mixin}: {@code wrapOperation} (the handler's) at {@code replacement}
     * in place of {@code replaced}, taking the host's arguments, the {@code Operation} and the handler's parameters after
     * its {@code Operation}. The handler stays as a plain method.
     */
    static void bridgeMethod(
            ClassBuilder builder,
            ClassModel mixin,
            MethodModel handler,
            Annotation wrapOperation,
            String replaced,
            String replacement) {
        MethodTypeDesc vanillaType = Call.parse(replaced).orElseThrow().type();
        MethodTypeDesc hostType = Call.parse(replacement).orElseThrow().type();
        MethodTypeDesc handlerType = handler.methodTypeSymbol();
        int vanillaArguments = vanillaType.parameterCount();
        int hostArguments = hostType.parameterCount();
        int own = hostArguments - 1 - vanillaArguments;
        List<ClassDesc> trailing =
                handlerType.parameterList().subList(vanillaArguments + 2, handlerType.parameterCount());
        List<ClassDesc> parameters = new ArrayList<>(hostType.parameterList());
        parameters.add(OPERATION);
        parameters.addAll(trailing);
        MethodTypeDesc bridgeType = MethodTypeDesc.of(handlerType.returnType(), parameters);
        int flags = handler.flags().flagsMask();
        boolean isStatic = (flags & ClassFile.ACC_STATIC) != 0;
        boolean isInterface = mixin.flags().has(AccessFlag.INTERFACE);
        ClassDesc owner = mixin.thisClass().asSymbol();
        String name = handler.methodName().stringValue();
        // The bridge's slots: the host's arguments (receiver first), Operation, trailing parameters.
        int[] slots = new int[parameters.size()];
        int next = isStatic ? 0 : 1;
        for (int index = 0; index < parameters.size(); index++) {
            slots[index] = next;
            next += TypeKind.from(parameters.get(index)).slotSize();
        }
        int operationSlot = slots[hostArguments];
        builder.withMethod(PREFIX + name, bridgeType, flags, method -> {
            method.with(RuntimeVisibleAnnotationsAttribute.of(
                    HostReplacedCalls.retargeted(wrapOperation, replaced, replacement)));
            // The handler's receiver and vanilla arguments have no annotations (fits); its trailing ones keep theirs.
            handler.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                    .ifPresent(attribute ->
                            method.with(RuntimeVisibleParameterAnnotationsAttribute.of(HostReplacedCalls.placed(
                                    attribute.parameterAnnotations(),
                                    vanillaArguments,
                                    parameters.size(),
                                    trailing.size()))));
            handler.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                    .ifPresent(attribute ->
                            method.with(RuntimeInvisibleParameterAnnotationsAttribute.of(HostReplacedCalls.placed(
                                    attribute.parameterAnnotations(),
                                    vanillaArguments,
                                    parameters.size(),
                                    trailing.size()))));
            method.withCode(code -> {
                if (!isStatic) {
                    code.aload(0);
                }
                // The receiver and the vanilla arguments, which follow the host's own ones.
                code.loadLocal(TypeKind.from(parameters.get(0)), slots[0]);
                for (int index = 0; index < vanillaArguments; index++) {
                    int at = 1 + own + index;
                    code.loadLocal(TypeKind.from(parameters.get(at)), slots[at]);
                }
                code.new_(INSERTED_ARGUMENTS);
                code.dup();
                code.aload(operationSlot);
                code.loadConstant(own);
                code.anewarray(ConstantDescs.CD_Object);
                for (int index = 0; index < own; index++) {
                    code.dup();
                    code.loadConstant(index);
                    HostReplacedCalls.loadBoxed(code, parameters.get(1 + index), slots[1 + index]);
                    code.aastore();
                }
                code.invokespecial(
                        INSERTED_ARGUMENTS,
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, OPERATION, ConstantDescs.CD_Object.arrayType()));
                for (int index = 0; index < trailing.size(); index++) {
                    int at = hostArguments + 1 + index;
                    code.loadLocal(TypeKind.from(parameters.get(at)), slots[at]);
                }
                if (isStatic) {
                    code.invokestatic(owner, name, handlerType, isInterface);
                } else {
                    code.invokespecial(owner, name, handlerType, isInterface);
                }
                code.return_(TypeKind.from(handlerType.returnType()));
            });
        });
    }
}
