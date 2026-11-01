package dev.plexusmodloader.compat.fabric.mixin;

import dev.plexusmodloader.compat.fabric.mixin.HostReplacedCalls.Call;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
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
 * A {@code @Redirect} of an instance call that the host's patch replaced, at the same place, with an overload on the same
 * owner taking one more argument after the vanilla ones ({@link
 * dev.plexusmodloader.compat.bridge.InjectionPointReplacement.Placement#HOST_NEUTRAL_ARGUMENT}): MinecraftForge
 * 26.2-65.1.5's and 26.3-66.1.0's {@code SectionCompiler.compile} calls {@code ModelBlockRenderer.tesselateBlock(output,
 * x, y, z, level, pos, state, model, seed, modelData)} where vanilla calls it without the {@code ModelData}, which Forge
 * looks up for the block's position and its model. Fabric API's renderer API redirects the vanilla call to draw every
 * block through the Fabric renderer.
 *
 * <p>The bridge ({@link #bridgeMethod}) wraps the host's call: when the host passes its neutral value ({@code
 * ModelData.EMPTY}, the same object), it calls the handler with the vanilla arguments, as the redirect would; otherwise
 * it makes the host's call, so that a model drawn with the host's data keeps it. Only a handler that takes the receiver
 * and the vanilla arguments with their types, and then nothing but annotated (MixinExtras sugar) parameters, is bridged.
 *
 * <p>Thread: any; no state.
 */
final class HostNeutralArguments {

    static final String PREFIX = "plexus$hostNeutralArgument$";

    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final ClassDesc WRAP_OPERATION =
            ClassDesc.ofDescriptor("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
    private static final ClassDesc OPERATION =
            ClassDesc.ofDescriptor("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;");

    private HostNeutralArguments() {}

    /** The rule's replacement split: the host's call and its neutral value's field. */
    record Rule(Call vanilla, Call host, ClassDesc neutralOwner, String neutralField) {

        static Optional<Rule> parse(String replaced, String replacement) {
            int hash = replacement.lastIndexOf('#');
            int semicolon = replacement.lastIndexOf(';');
            if (hash < 0 || semicolon < hash) {
                return Optional.empty();
            }
            Optional<Call> vanilla = Call.parse(replaced);
            Optional<Call> host = Call.parse(replacement.substring(0, hash));
            if (vanilla.isEmpty() || host.isEmpty()) {
                return Optional.empty();
            }
            MethodTypeDesc vanillaType = vanilla.get().type();
            MethodTypeDesc hostType = host.get().type();
            if (!vanilla.get().owner().equals(host.get().owner())
                    || !vanilla.get().name().equals(host.get().name())
                    || hostType.parameterCount() != vanillaType.parameterCount() + 1
                    || !hostType.returnType().equals(vanillaType.returnType())
                    || !hostType.parameterList()
                            .subList(0, vanillaType.parameterCount())
                            .equals(vanillaType.parameterList())
                    || hostType.parameterType(vanillaType.parameterCount()).isPrimitive()) {
                return Optional.empty();
            }
            return Optional.of(new Rule(
                    vanilla.get(),
                    host.get(),
                    ClassDesc.ofDescriptor(replacement.substring(hash + 1, semicolon + 1)),
                    replacement.substring(semicolon + 1)));
        }

        String hostCall() {
            return host.owner() + host.name() + host.type().descriptorString();
        }

        ClassDesc appended() {
            return host.type().parameterType(vanilla.type().parameterCount());
        }
    }

    /**
     * Whether a {@code @Redirect} handler {@code handler} of {@code replaced} can run through the bridge: the rule
     * parses, the handler is a {@code @Redirect} taking the receiver and the vanilla arguments with their types, then
     * only annotated parameters, and returns the call's result.
     */
    static boolean fits(MethodModel handler, String type, String replaced, String replacement) {
        Optional<Rule> rule = Rule.parse(replaced, replacement);
        if (!type.equals(REDIRECT) || rule.isEmpty()) {
            return false;
        }
        MethodTypeDesc vanillaType = rule.get().vanilla().type();
        MethodTypeDesc handlerType = handler.methodTypeSymbol();
        int arguments = vanillaType.parameterCount();
        if (handlerType.parameterCount() < arguments + 1
                || !handlerType.returnType().equals(vanillaType.returnType())
                || !handlerType
                        .parameterType(0)
                        .descriptorString()
                        .equals(rule.get().vanilla().owner())
                || !handlerType.parameterList().subList(1, arguments + 1).equals(vanillaType.parameterList())
                || HostReplacedCalls.annotatedBefore(handler, arguments + 1)) {
            return false;
        }
        for (int index = arguments + 1; index < handlerType.parameterCount(); index++) {
            if (!annotated(handler, index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean annotated(MethodModel handler, int index) {
        boolean visible = handler.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                .map(attribute -> index < attribute.parameterAnnotations().size()
                        && !attribute.parameterAnnotations().get(index).isEmpty())
                .orElse(false);
        boolean invisible = handler.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                .map(attribute -> index < attribute.parameterAnnotations().size()
                        && !attribute.parameterAnnotations().get(index).isEmpty())
                .orElse(false);
        return visible || invisible;
    }

    /** Whether a planned call with {@code replaced} and {@code replacement} is one of these. */
    static boolean covers(String replaced, String replacement) {
        return Rule.parse(replaced, replacement).isPresent();
    }

    /**
     * Adds the bridge of {@code handler} to {@code mixin}: a {@code @WrapOperation} at the host's call in the methods
     * {@code redirect} (the handler's) names, taking the receiver, the host's arguments, the {@code Operation} and the
     * handler's annotated parameters. The handler stays as a plain method.
     */
    static void bridgeMethod(
            ClassBuilder builder,
            ClassModel mixin,
            MethodModel handler,
            Annotation redirect,
            String replaced,
            String replacement) {
        Rule rule = Rule.parse(replaced, replacement).orElseThrow();
        MethodTypeDesc vanillaType = rule.vanilla().type();
        MethodTypeDesc handlerType = handler.methodTypeSymbol();
        int arguments = vanillaType.parameterCount();
        List<ClassDesc> trailing = handlerType.parameterList().subList(arguments + 1, handlerType.parameterCount());
        ClassDesc receiver = ClassDesc.ofDescriptor(rule.vanilla().owner());
        List<ClassDesc> parameters = new ArrayList<>();
        parameters.add(receiver);
        parameters.addAll(vanillaType.parameterList());
        parameters.add(rule.appended());
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
        int appendedIndex = arguments + 1;
        int operationIndex = arguments + 2;
        builder.withMethod(PREFIX + name, bridgeType, flags, method -> {
            method.with(RuntimeVisibleAnnotationsAttribute.of(wrapOperation(redirect, rule.hostCall())));
            handler.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                    .ifPresent(attribute -> method.with(RuntimeVisibleParameterAnnotationsAttribute.of(
                            placed(attribute.parameterAnnotations(), arguments, parameters.size(), trailing.size()))));
            handler.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                    .ifPresent(attribute -> method.with(RuntimeInvisibleParameterAnnotationsAttribute.of(
                            placed(attribute.parameterAnnotations(), arguments, parameters.size(), trailing.size()))));
            method.withCode(code -> {
                Label host = code.newLabel();
                code.aload(slots[appendedIndex]);
                code.getstatic(rule.neutralOwner(), rule.neutralField(), rule.appended());
                code.if_acmpne(host);
                // The host asks for nothing more than the vanilla call: the redirect runs as written.
                if (!isStatic) {
                    code.aload(0);
                }
                for (int index = 0; index <= arguments; index++) {
                    code.loadLocal(TypeKind.from(parameters.get(index)), slots[index]);
                }
                for (int index = 0; index < trailing.size(); index++) {
                    int at = operationIndex + 1 + index;
                    code.loadLocal(TypeKind.from(parameters.get(at)), slots[at]);
                }
                if (isStatic) {
                    code.invokestatic(owner, name, handlerType, isInterface);
                } else {
                    code.invokespecial(owner, name, handlerType, isInterface);
                }
                code.return_(TypeKind.from(handlerType.returnType()));
                // Otherwise the host's call, with the host's argument.
                code.labelBinding(host);
                code.aload(slots[operationIndex]);
                code.loadConstant(arguments + 2);
                code.anewarray(ConstantDescs.CD_Object);
                for (int index = 0; index <= appendedIndex; index++) {
                    code.dup();
                    code.loadConstant(index);
                    HostReplacedCalls.loadBoxed(code, parameters.get(index), slots[index]);
                    code.aastore();
                }
                code.invokeinterface(
                        OPERATION,
                        "call",
                        MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object.arrayType()));
                returnUnboxed(code, handlerType.returnType());
            });
        });
    }

    /** The {@code @WrapOperation} of the host's call {@code hostCall} in the methods and slice {@code redirect} names. */
    private static Annotation wrapOperation(Annotation redirect, String hostCall) {
        List<AnnotationElement> elements = new ArrayList<>();
        for (AnnotationElement element : redirect.elements()) {
            String elementName = element.name().stringValue();
            if (elementName.equals("at") && element.value() instanceof AnnotationValue.OfAnnotation at) {
                elements.add(AnnotationElement.of(
                        "at",
                        AnnotationValue.ofArray(AnnotationValue.ofAnnotation(retargeted(at.annotation(), hostCall)))));
            } else if (elementName.equals("slice") && element.value() instanceof AnnotationValue.OfAnnotation slice) {
                elements.add(AnnotationElement.of("slice", AnnotationValue.ofArray(slice)));
            } else if (elementName.equals("method")
                    || elementName.equals("slice")
                    || elementName.equals("require")
                    || elementName.equals("expect")
                    || elementName.equals("allow")) {
                elements.add(element);
            }
        }
        return Annotation.of(WRAP_OPERATION, elements);
    }

    private static Annotation retargeted(Annotation at, String hostCall) {
        List<AnnotationElement> elements = new ArrayList<>();
        for (AnnotationElement element : at.elements()) {
            if (element.name().equalsString("target")) {
                elements.add(AnnotationElement.ofString("target", hostCall));
            } else {
                elements.add(element);
            }
        }
        return Annotation.of(ClassDesc.ofDescriptor(AT), elements);
    }

    /** The handler's annotations for the bridge: none before the {@code Operation}, its own after it. */
    private static List<List<Annotation>> placed(
            List<List<Annotation>> annotations, int arguments, int count, int trailing) {
        List<List<Annotation>> placed = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            placed.add(List.of());
        }
        for (int index = 0; index < trailing; index++) {
            int from = arguments + 1 + index;
            if (from < annotations.size()) {
                placed.set(count - trailing + index, annotations.get(from));
            }
        }
        return placed;
    }

    private static void returnUnboxed(CodeBuilder code, ClassDesc type) {
        TypeKind kind = TypeKind.from(type);
        switch (kind) {
            case VOID -> {
                code.pop();
                code.return_();
            }
            case REFERENCE -> {
                code.checkcast(type);
                code.areturn();
            }
            default -> {
                ClassDesc box = switch (kind) {
                    case BOOLEAN -> ConstantDescs.CD_Boolean;
                    case BYTE -> ConstantDescs.CD_Byte;
                    case CHAR -> ConstantDescs.CD_Character;
                    case SHORT -> ConstantDescs.CD_Short;
                    case INT -> ConstantDescs.CD_Integer;
                    case LONG -> ConstantDescs.CD_Long;
                    case FLOAT -> ConstantDescs.CD_Float;
                    default -> ConstantDescs.CD_Double;
                };
                code.checkcast(box);
                code.invokevirtual(box, type.displayName() + "Value", MethodTypeDesc.of(type));
                code.return_(kind);
            }
        }
    }
}
