package dev.plexusmodloader.compat.fabric.mixin;

import dev.plexusmodloader.compat.fabric.mixin.HostReplacedCalls.Call;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A {@code @WrapOperation} of an instance call that the host's patch replaced, at the same place, with a call of a
 * method of the target class itself on the target instance, with the same arguments and result, which makes the vanilla
 * call on a receiver the target class hands out ({@link
 * dev.plexusmodloader.compat.bridge.InjectionPointReplacement.Placement#SAME_PLACE}): MinecraftForge 26.2-65.1.5's and
 * 26.3-66.1.0's {@code Gui.extractRenderState} calls {@code this.drawScreen(graphics, mouseX, mouseY, partialTick)}
 * (inherited from Forge's {@code ForgeLayerInstance}, which draws Forge's screen layers and then the current screen)
 * where vanilla calls {@code screen.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, partialTick)},
 * both only while {@code screen} is set. Fabric API's screen events wrap the vanilla call.
 *
 * <p>The bridge ({@link #bridgeMethod}) wraps the host's call and calls the handler with the vanilla receiver, read
 * through the target class's only public instance method without parameters that returns the receiver's type (Forge's
 * {@code Gui.screen()}), the arguments and a {@link ReplacedReceiver}, which makes the host's call when the handler makes
 * the wrapped one. The handler thus runs as written around the host's call, which does what the vanilla call did and
 * what the host added to it. Only a handler that makes the call once, as given, is bridged ({@link
 * HostReplacedCalls#callsOnceWithOwnArguments}).
 *
 * <p>Thread: any; no state.
 */
final class HostTargetCalls {

    static final String PREFIX = "plexus$hostTargetCall$";

    private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final ClassDesc OPERATION =
            ClassDesc.ofDescriptor("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;");
    private static final ClassDesc REPLACED_RECEIVER = ClassDesc.of(ReplacedReceiver.class.getName());

    private HostTargetCalls() {}

    /**
     * The getter of the vanilla receiver when {@code replacement} is a call of a method of {@code target} (the patched
     * target class) with {@code replaced}'s arguments and result, and {@code target} has exactly one public instance
     * method without parameters that returns {@code replaced}'s owner: its name. Empty otherwise.
     */
    static Optional<String> receiverGetter(ClassModel target, String replaced, String replacement) {
        Optional<Call> vanilla = Call.parse(replaced);
        Optional<Call> host = Call.parse(replacement);
        String targetDescriptor = target.thisClass().asSymbol().descriptorString();
        if (vanilla.isEmpty()
                || host.isEmpty()
                || !host.get().owner().equals(targetDescriptor)
                || vanilla.get().owner().equals(targetDescriptor)
                || !host.get().type().equals(vanilla.get().type())) {
            return Optional.empty();
        }
        String getterType = "()" + vanilla.get().owner();
        List<String> getters = target.methods().stream()
                .filter(method -> method.flags().has(AccessFlag.PUBLIC)
                        && !method.flags().has(AccessFlag.STATIC)
                        && method.methodType().equalsString(getterType))
                .map(method -> method.methodName().stringValue())
                .toList();
        return getters.size() == 1 ? Optional.of(getters.getFirst()) : Optional.empty();
    }

    /** Whether {@code replacement}, as {@link #encode} wrote it, names a host call of this kind. */
    static boolean covers(String replacement) {
        return replacement.indexOf('#') > 0;
    }

    /** {@code replacement} with the receiver getter's name, as the planned call carries it. */
    static String encode(String replacement, String getter) {
        return replacement + "#" + getter;
    }

    /** The host call {@link #encode} was given. */
    static String call(String encoded) {
        return encoded.substring(0, encoded.lastIndexOf('#'));
    }

    /**
     * Whether a {@code @WrapOperation} handler {@code handler} of {@code replaced} can run through the bridge: it takes
     * the receiver, the vanilla arguments with their types, the {@code Operation}, maybe the target method's parameters
     * after it, returns the call's result, and makes the wrapped call once, as given.
     */
    static boolean fits(MethodModel handler, String type, String replaced) {
        if (!type.equals(WRAP_OPERATION)) {
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

    /**
     * Adds the bridge of {@code handler} to {@code mixin}: {@code wrapOperation} (the handler's) at the host call in
     * {@code encoded} in place of {@code replaced}, taking the target instance, the arguments, the {@code Operation} and
     * the handler's parameters after its {@code Operation}. The handler stays as a plain method.
     */
    static void bridgeMethod(
            ClassBuilder builder,
            ClassModel mixin,
            MethodModel handler,
            Annotation wrapOperation,
            String replaced,
            String encoded) {
        String replacement = call(encoded);
        String getter = encoded.substring(encoded.lastIndexOf('#') + 1);
        Call vanilla = Call.parse(replaced).orElseThrow();
        Call host = Call.parse(replacement).orElseThrow();
        MethodTypeDesc handlerType = handler.methodTypeSymbol();
        int arguments = vanilla.type().parameterCount();
        List<ClassDesc> trailing = handlerType.parameterList().subList(arguments + 2, handlerType.parameterCount());
        ClassDesc target = ClassDesc.ofDescriptor(host.owner());
        ClassDesc receiver = ClassDesc.ofDescriptor(vanilla.owner());
        List<ClassDesc> parameters = new ArrayList<>();
        parameters.add(target);
        parameters.addAll(vanilla.type().parameterList());
        parameters.add(OPERATION);
        parameters.addAll(trailing);
        MethodTypeDesc bridgeType = MethodTypeDesc.of(handlerType.returnType(), parameters);
        int flags = handler.flags().flagsMask();
        boolean isStatic = (flags & ClassFile.ACC_STATIC) != 0;
        boolean isInterface = mixin.flags().has(AccessFlag.INTERFACE);
        ClassDesc owner = mixin.thisClass().asSymbol();
        String name = handler.methodName().stringValue();
        int[] slots = new int[parameters.size()];
        int next = isStatic ? 0 : 1;
        for (int index = 0; index < parameters.size(); index++) {
            slots[index] = next;
            next += TypeKind.from(parameters.get(index)).slotSize();
        }
        int operationSlot = slots[arguments + 1];
        builder.withMethod(PREFIX + name, bridgeType, flags, method -> {
            method.with(RuntimeVisibleAnnotationsAttribute.of(
                    HostReplacedCalls.retargeted(wrapOperation, replaced, replacement)));
            // The handler's receiver and arguments have no annotations (fits); its trailing ones keep theirs.
            handler.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                    .ifPresent(attribute ->
                            method.with(RuntimeVisibleParameterAnnotationsAttribute.of(HostReplacedCalls.placed(
                                    attribute.parameterAnnotations(), arguments, parameters.size(), trailing.size()))));
            handler.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                    .ifPresent(attribute ->
                            method.with(RuntimeInvisibleParameterAnnotationsAttribute.of(HostReplacedCalls.placed(
                                    attribute.parameterAnnotations(), arguments, parameters.size(), trailing.size()))));
            method.withCode(code -> {
                if (!isStatic) {
                    code.aload(0);
                }
                // The vanilla receiver, through the target's getter.
                code.aload(slots[0]);
                code.invokevirtual(target, getter, MethodTypeDesc.of(receiver));
                for (int index = 0; index < arguments; index++) {
                    code.loadLocal(TypeKind.from(parameters.get(index + 1)), slots[index + 1]);
                }
                code.new_(REPLACED_RECEIVER);
                code.dup();
                code.aload(operationSlot);
                code.aload(slots[0]);
                code.invokespecial(
                        REPLACED_RECEIVER,
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, OPERATION, ConstantDescs.CD_Object));
                for (int index = 0; index < trailing.size(); index++) {
                    int at = arguments + 2 + index;
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
