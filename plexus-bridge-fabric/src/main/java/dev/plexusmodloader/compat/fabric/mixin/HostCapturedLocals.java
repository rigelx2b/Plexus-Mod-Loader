package dev.plexusmodloader.compat.fabric.mixin;

import dev.plexusmodloader.compat.bridge.HostedLaunch;
import dev.plexusmodloader.compat.bridge.InjectionPointReplacement;
import dev.plexusmodloader.compat.fabric.mixin.HostPatchAdjustments.Finding;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.LocalVariableInfo;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Locals a Fabric mixin's {@code @Inject} captures by position ({@code locals = LocalCapture.CAPTURE_*}) in a method the
 * host loader's patch recompiled (ADR-0021 G8). The obfuscated vanilla method's stack map frames leave its dead locals
 * out, so Mixin lists only the live ones at an injection point, and the mod's handler takes those; MinecraftForge
 * 1.20.x's patched classes are compiled by {@code javac}, whose frames keep every local of a scope, so Mixin lists more
 * there (Cardinal Components API 5.2.3's {@code MixinFallingBlockEntity.readComponentData} in {@code
 * FallingBlockEntity.tick}: it takes {@code Block, BlockPos, BlockEntity, CompoundTag}, and the patched method has {@code
 * Block, BlockPos, Z, Z, D, BlockState, Z, Z, Z,
 * BlockEntity, CompoundTag} there; "LVT ... has incompatible changes").
 *
 * <p>When the patch changed the method's code and the patched method has a local variable table in which each captured
 * type is the type of exactly one local (in scope at the injection's call, for a soft capture), the handler captures
 * each local by
 * its type instead (MixinExtras' {@code @Local} without name, ordinal or index, the one local of that type in scope),
 * and its {@code @Inject} no longer captures locals ({@code PLEXUS-COMPAT-212}). The values the handler gets are the
 * locals it was written for: one local of each type exists in the method. Otherwise the injection is left as it is.
 */
final class HostCapturedLocals {

    static final HostCapturedLocals NONE = new HostCapturedLocals(Map.of());

    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
    private static final Set<String> CALLBACK_INFOS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;",
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;");
    private static final Set<String> CAPTURES = Set.of("CAPTURE_FAILHARD", "CAPTURE_FAILSOFT", "CAPTURE_FAILEXCEPTION");

    /** Handler {@code handler descriptor}: its parameters from {@code firstLocal} on are captured by type. */
    private record Conversion(String handler, String descriptor, int firstLocal) {}

    /** Handler {@code handler descriptor}: its injection moves from right after its call to right before it. */
    private record MovedBefore(String handler, String descriptor) {}

    private final Map<String, List<Conversion>> conversions;
    private final Map<String, List<MovedBefore>> movedBefore;

    private HostCapturedLocals(Map<String, List<Conversion>> conversions) {
        this(conversions, Map.of());
    }

    private HostCapturedLocals(Map<String, List<Conversion>> conversions, Map<String, List<MovedBefore>> movedBefore) {
        this.conversions = conversions;
        this.movedBefore = movedBefore;
    }

    /** Collects the conversions while {@link HostPatchAdjustments#plan} goes through the mixins. Not thread-safe. */
    static final class Planner {

        private final HostedLaunch host;
        private final HostPatchAdjustments.Classes classes;
        private final Map<String, List<Conversion>> conversions = new HashMap<>();
        private final Map<String, List<MovedBefore>> movedBefore = new HashMap<>();

        Planner(HostedLaunch host, HostPatchAdjustments.Classes classes) {
            this.host = host;
            this.classes = classes;
        }

        /** The findings for {@code mixin} of mod {@code modId}, from configuration {@code config}. */
        List<Finding> check(String modId, String config, ClassModel mixin) {
            String mixinName = mixin.thisClass().asInternalName();
            List<Finding> findings = new ArrayList<>();
            Set<Conversion> own = new LinkedHashSet<>();
            Set<MovedBefore> ownMoved = new LinkedHashSet<>();
            for (String targetName : HostPatchAdjustments.mixinTargets(mixin)) {
                Optional<ClassModel> vanilla = classes.vanilla(targetName);
                Optional<ClassModel> patched = classes.patched(targetName);
                if (vanilla.isEmpty() || patched.isEmpty()) {
                    continue;
                }
                for (MethodModel handler : mixin.methods()) {
                    Optional<Annotation> inject = HostPatchAdjustments.annotation(handler.attributes(), INJECT);
                    if (inject.isEmpty() || !capturesLocals(inject.get())) {
                        continue;
                    }
                    Optional<Integer> firstLocal = firstCapturedLocal(handler);
                    if (firstLocal.isEmpty()) {
                        continue;
                    }
                    // Capture by type writes the parameters' annotations; moving before the call keeps them.
                    boolean annotatedParameters = hasParameterAnnotations(handler);
                    List<ClassDesc> captured = handler.methodTypeSymbol()
                            .parameterList()
                            .subList(
                                    firstLocal.get(), handler.methodTypeSymbol().parameterCount());
                    // Capture by type needs one captured local of each type; moving before the call does not.
                    boolean distinctTypes = new HashSet<>(captured).size() == captured.size();
                    for (String selector : selectors(inject.get())) {
                        String name = selector.replaceFirst("^L[^;]+;", "");
                        String descriptor = name.contains("(") ? name.substring(name.indexOf('(')) : null;
                        name = descriptor == null ? name : name.substring(0, name.indexOf('('));
                        List<MethodModel> before = HostPatchAdjustments.methods(vanilla.get(), name, descriptor);
                        List<MethodModel> after = HostPatchAdjustments.methods(patched.get(), name, descriptor);
                        Optional<InjectionPointReplacement> rule = beforeCallRule(mixinName, handler, targetName, name);
                        String mismatch = rule.isEmpty()
                                ? null
                                : after.size() != 1
                                        ? after.size() + " methods of that name in the patched class"
                                        : beforeCallMismatch(
                                                after.getFirst(),
                                                captured,
                                                inject.get(),
                                                rule.get().replacement());
                        if (rule.isPresent() && mismatch != null) {
                            // A rule written for this handler that does not fit: the injection stays, and says why.
                            findings.add(new Finding(
                                    modId,
                                    config,
                                    mixinName,
                                    "@Inject " + handler.methodName().stringValue() + " into " + targetName + "."
                                            + name + ": " + host.hostName() + "'s rule moves it to right before "
                                            + rule.get().replacement() + ", but " + mismatch
                                            + "; the injection is left as it is",
                                    false,
                                    "PLEXUS-COMPAT-212",
                                    false));
                        }
                        if (rule.isPresent() && mismatch == null) {
                            MovedBefore moved = new MovedBefore(
                                    handler.methodName().stringValue(),
                                    handler.methodType().stringValue());
                            if (ownMoved.add(moved)) {
                                findings.add(new Finding(
                                        modId,
                                        config,
                                        mixinName,
                                        "@Inject " + handler.methodName().stringValue() + " into " + targetName + "."
                                                + name + ": " + host.hostName() + "'s patch ends the scope of a local"
                                                + " it captures at "
                                                + rule.get().replacement() + "; the injection"
                                                + " moves from right after that call to right before it, where the "
                                                + captured.size() + " locals it captures are in scope",
                                        false,
                                        "PLEXUS-COMPAT-212",
                                        false));
                            }
                            continue;
                        }
                        if (!distinctTypes
                                || annotatedParameters
                                || before.size() != 1
                                || after.size() != 1
                                || !convertible(
                                        before.getFirst(),
                                        after.getFirst(),
                                        captured,
                                        softCapture(inject.get()),
                                        invokeTarget(inject.get()))) {
                            continue;
                        }
                        Conversion conversion = new Conversion(
                                handler.methodName().stringValue(),
                                handler.methodType().stringValue(),
                                firstLocal.get());
                        if (own.add(conversion)) {
                            findings.add(new Finding(
                                    modId,
                                    config,
                                    mixinName,
                                    "@Inject " + handler.methodName().stringValue() + " into " + targetName + "."
                                            + name + ": " + host.hostName() + "'s patch keeps the method's locals in"
                                            + " other slots; the handler captures each of its " + captured.size()
                                            + " locals by type (MixinExtras @Local), the one local of that type in"
                                            + " the method",
                                    false,
                                    "PLEXUS-COMPAT-212",
                                    false));
                        }
                    }
                }
            }
            if (!own.isEmpty()) {
                conversions.put(mixinName, List.copyOf(own));
            }
            if (!ownMoved.isEmpty()) {
                movedBefore.put(mixinName, List.copyOf(ownMoved));
            }
            return findings;
        }

        /** The host's {@code BEFORE_CALL} rule for {@code handler} of {@code mixinName} into {@code method}. */
        private Optional<InjectionPointReplacement> beforeCallRule(
                String mixinName, MethodModel handler, String targetName, String method) {
            String key = mixinName + "#" + handler.methodName().stringValue();
            String keyWithDescriptor = key + handler.methodType().stringValue();
            return host.injectionPointReplacements().stream()
                    .filter(rule -> rule.placement() == InjectionPointReplacement.Placement.BEFORE_CALL
                            && rule.targetClass().equals(targetName)
                            && rule.method().equals(method)
                            && (rule.replaced().equals(key) || rule.replaced().equals(keyWithDescriptor)))
                    .findFirst();
        }

        HostCapturedLocals build() {
            return conversions.isEmpty() && movedBefore.isEmpty()
                    ? NONE
                    : new HostCapturedLocals(Map.copyOf(conversions), Map.copyOf(movedBefore));
        }
    }

    /**
     * Whether an injection right after the call {@code call} ({@code Lowner;name(descriptor)}) capturing {@code
     * captured} by position moves to right before it in {@code patched}: the injection is at that call with {@code
     * shift = AFTER}, the method makes the call once, and in its local variable table the locals after the parameters
     * are {@code captured}, in slot order, at the call and are not right after it.
     */
    static boolean movesBeforeCall(MethodModel patched, List<ClassDesc> captured, Annotation inject, String call) {
        return beforeCallMismatch(patched, captured, inject, call) == null;
    }

    /** Why the injection does not move ({@link #movesBeforeCall}), or null when it does. */
    static String beforeCallMismatch(MethodModel patched, List<ClassDesc> captured, Annotation inject, String call) {
        if (!call.equals(invokeTarget(inject))) {
            return "it injects at " + invokeTarget(inject);
        }
        if (!shiftsAfter(inject)) {
            return "it is not shifted after the call";
        }
        Optional<CodeAttribute> code = patched.findAttribute(Attributes.code());
        if (code.isEmpty()) {
            return "the patched method has no code";
        }
        List<int[]> spans = invokeSpans(code.get(), call);
        if (spans.size() != 1) {
            return "the patched method makes that call " + spans.size() + " times";
        }
        List<LocalVariableInfo> table = code.get().findAttributes(Attributes.localVariableTable()).stream()
                .flatMap(attribute -> attribute.localVariables().stream())
                .toList();
        if (table.isEmpty()) {
            return "the patched method has no local variable table";
        }
        int firstLocal = patched.flags().has(java.lang.reflect.AccessFlag.STATIC) ? 0 : 1;
        for (ClassDesc parameter : patched.methodTypeSymbol().parameterList()) {
            firstLocal += parameter.equals(java.lang.constant.ConstantDescs.CD_long)
                            || parameter.equals(java.lang.constant.ConstantDescs.CD_double)
                    ? 2
                    : 1;
        }
        int offset = spans.getFirst()[0];
        int next = offset + spans.getFirst()[1];
        List<ClassDesc> atCall = localsAt(table, firstLocal, offset);
        if (!captured.equals(atCall)) {
            return "its locals at the call are " + atCall;
        }
        if (captured.equals(localsAt(table, firstLocal, next))) {
            return "they are in scope right after the call too";
        }
        return null;
    }

    /** The types of the locals from slot {@code firstLocal} on in scope at {@code offset}, in slot order. */
    private static List<ClassDesc> localsAt(List<LocalVariableInfo> table, int firstLocal, int offset) {
        return table.stream()
                .filter(local -> local.slot() >= firstLocal
                        && local.startPc() <= offset
                        && offset < local.startPc() + local.length())
                .sorted(java.util.Comparator.comparingInt(LocalVariableInfo::slot))
                .map(LocalVariableInfo::typeSymbol)
                .toList();
    }

    /** Whether the injector's one {@code @At} has {@code shift = AFTER}. */
    private static boolean shiftsAfter(Annotation inject) {
        return points(inject).stream()
                .flatMap(point -> point.elements().stream())
                .anyMatch(element -> element.name().equalsString("shift")
                        && element.value() instanceof AnnotationValue.OfEnum shift
                        && shift.constantName().equalsString("AFTER"));
    }

    /**
     * Whether a capture of {@code captured} in {@code vanilla} moves to capture by type in {@code patched}: the patch
     * changed the method's code, and {@code patched}'s local variable table has exactly one local of each type. Where
     * positional capture finds such a local, the capture by type finds the same one. A {@code soft} capture ({@code
     * CAPTURE_FAILSOFT}) only moves when it injects at calls of {@code invokeTarget} and each such local is in scope at
     * each of those calls: where the capture would not find its locals, the injection is skipped, and the
     * capture by type would stop the game instead.
     */
    static boolean convertible(
            MethodModel vanilla, MethodModel patched, List<ClassDesc> captured, boolean soft, String invokeTarget) {
        Optional<CodeAttribute> vanillaCode = vanilla.findAttribute(Attributes.code());
        Optional<CodeAttribute> patchedCode = patched.findAttribute(Attributes.code());
        if (vanillaCode.isEmpty() || patchedCode.isEmpty()) {
            return false;
        }
        if (Arrays.equals(vanillaCode.get().codeArray(), patchedCode.get().codeArray())) {
            return false;
        }
        List<LocalVariableInfo> table = patchedCode.get().findAttributes(Attributes.localVariableTable()).stream()
                .flatMap(attribute -> attribute.localVariables().stream())
                .toList();
        if (table.isEmpty()) {
            return false;
        }
        List<Integer> calls = soft ? invokeOffsets(patchedCode.get(), invokeTarget) : List.of();
        if (soft && calls.isEmpty()) {
            return false;
        }
        for (ClassDesc type : captured) {
            List<LocalVariableInfo> ofType = table.stream()
                    .filter(local -> local.typeSymbol().equals(type))
                    .toList();
            if (ofType.stream().map(LocalVariableInfo::slot).distinct().count() != 1) {
                return false;
            }
            for (int call : calls) {
                if (ofType.stream()
                        .noneMatch(local -> local.startPc() <= call && call < local.startPc() + local.length())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The offsets of the calls {@code target} ({@code Lowner;name(descriptor)}) in {@code code}; empty for null. */
    private static List<Integer> invokeOffsets(CodeAttribute code, String target) {
        return invokeSpans(code, target).stream().map(span -> span[0]).toList();
    }

    /** The offset and size of each call {@code target} ({@code Lowner;name(descriptor)}) in {@code code}. */
    private static List<int[]> invokeSpans(CodeAttribute code, String target) {
        if (target == null) {
            return List.of();
        }
        List<int[]> spans = new ArrayList<>();
        int offset = 0;
        for (CodeElement element : code.elementList()) {
            if (!(element instanceof Instruction instruction)) {
                continue;
            }
            if (instruction instanceof InvokeInstruction invoke
                    && target.equals("L" + invoke.owner().asInternalName() + ";"
                            + invoke.name().stringValue() + invoke.type().stringValue())) {
                spans.add(new int[] {offset, instruction.sizeInBytes()});
            }
            offset += instruction.sizeInBytes();
        }
        return spans;
    }

    /** The injector's {@code @At} annotations. */
    private static List<Annotation> points(Annotation inject) {
        List<Annotation> points = new ArrayList<>();
        for (AnnotationElement element : inject.elements()) {
            if (!element.name().equalsString("at")) {
                continue;
            }
            if (element.value() instanceof AnnotationValue.OfAnnotation single) {
                points.add(single.annotation());
            } else if (element.value() instanceof AnnotationValue.OfArray array) {
                for (AnnotationValue value : array.values()) {
                    if (value instanceof AnnotationValue.OfAnnotation point) {
                        points.add(point.annotation());
                    }
                }
            }
        }
        return points;
    }

    /** The {@code target} of the injector's {@code @At("INVOKE")}, when it has exactly one point and it is a call. */
    private static String invokeTarget(Annotation inject) {
        List<Annotation> points = points(inject);
        if (points.size() != 1) {
            return null;
        }
        String kind = null;
        String target = null;
        for (AnnotationElement element : points.getFirst().elements()) {
            if (element.value() instanceof AnnotationValue.OfString text) {
                if (element.name().equalsString("value")) {
                    kind = text.stringValue();
                } else if (element.name().equalsString("target")) {
                    target = text.stringValue();
                }
            }
        }
        return "INVOKE".equals(kind) ? target : null;
    }

    private static boolean softCapture(Annotation inject) {
        for (AnnotationElement element : inject.elements()) {
            if (element.name().equalsString("locals")
                    && element.value() instanceof AnnotationValue.OfEnum capture
                    && capture.constantName().equalsString("CAPTURE_FAILSOFT")) {
                return true;
            }
        }
        return false;
    }

    private static boolean capturesLocals(Annotation inject) {
        for (AnnotationElement element : inject.elements()) {
            if (element.name().equalsString("locals")
                    && element.value() instanceof AnnotationValue.OfEnum capture
                    && CAPTURES.contains(capture.constantName().stringValue())) {
                return true;
            }
        }
        return false;
    }

    /** The index of the first parameter after the handler's {@code CallbackInfo}, when it has captured locals. */
    private static Optional<Integer> firstCapturedLocal(MethodModel handler) {
        MethodTypeDesc type = handler.methodTypeSymbol();
        for (int index = 0; index < type.parameterCount(); index++) {
            if (CALLBACK_INFOS.contains(type.parameterType(index).descriptorString())) {
                return index + 1 < type.parameterCount() ? Optional.of(index + 1) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** A handler whose parameters carry annotations (sugar, already) is left as it is. */
    private static boolean hasParameterAnnotations(MethodModel handler) {
        return handler.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                        .map(attribute ->
                                attribute.parameterAnnotations().stream().anyMatch(list -> !list.isEmpty()))
                        .orElse(false)
                || handler.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                        .map(attribute ->
                                attribute.parameterAnnotations().stream().anyMatch(list -> !list.isEmpty()))
                        .orElse(false);
    }

    private static List<String> selectors(Annotation inject) {
        List<String> selectors = new ArrayList<>();
        for (AnnotationElement element : inject.elements()) {
            if (element.name().equalsString("method") && element.value() instanceof AnnotationValue.OfArray values) {
                for (AnnotationValue value : values.values()) {
                    if (value instanceof AnnotationValue.OfString text) {
                        selectors.add(text.stringValue());
                    }
                }
            } else if (element.name().equalsString("method")
                    && element.value() instanceof AnnotationValue.OfString text) {
                selectors.add(text.stringValue());
            }
        }
        return selectors;
    }

    /**
     * {@code bytes} of mixin {@code mixin} with the planned handlers capturing their locals by type, and the planned
     * injections moved to right before their calls.
     */
    byte[] apply(String mixin, byte[] bytes) {
        byte[] moved = moveBefore(mixin, bytes);
        List<Conversion> own = conversions.get(mixin);
        if (own == null) {
            return moved;
        }
        ClassFile classFile = ClassFile.of();
        return classFile.transformClass(classFile.parse(moved), (builder, element) -> {
            if (!(element instanceof MethodModel method)) {
                builder.with(element);
                return;
            }
            Optional<Conversion> conversion = own.stream()
                    .filter(candidate -> method.methodName().equalsString(candidate.handler())
                            && method.methodType().equalsString(candidate.descriptor()))
                    .findFirst();
            if (conversion.isEmpty()) {
                builder.with(element);
                return;
            }
            int parameters = method.methodTypeSymbol().parameterCount();
            builder.withMethod(
                    method.methodName().stringValue(),
                    method.methodTypeSymbol(),
                    method.flags().flagsMask(),
                    methodBuilder -> {
                        for (MethodElement methodElement : method) {
                            if (methodElement instanceof AccessFlags
                                    || methodElement instanceof RuntimeInvisibleParameterAnnotationsAttribute) {
                                continue;
                            } else if (methodElement instanceof RuntimeVisibleAnnotationsAttribute visible) {
                                methodBuilder.with(RuntimeVisibleAnnotationsAttribute.of(visible.annotations().stream()
                                        .map(annotation ->
                                                annotation.className().equalsString(INJECT)
                                                        ? withoutLocals(annotation)
                                                        : annotation)
                                        .toList()));
                            } else {
                                methodBuilder.with(methodElement);
                            }
                        }
                        List<List<Annotation>> annotations = new ArrayList<>();
                        for (int index = 0; index < parameters; index++) {
                            annotations.add(
                                    index >= conversion.get().firstLocal()
                                            ? List.of(Annotation.of(ClassDesc.ofDescriptor(LOCAL)))
                                            : List.of());
                        }
                        methodBuilder.with(RuntimeInvisibleParameterAnnotationsAttribute.of(annotations));
                    });
        });
    }

    /** {@code bytes} with the {@code @At} of each planned {@link MovedBefore} handler of {@code mixin} without shift. */
    private byte[] moveBefore(String mixin, byte[] bytes) {
        List<MovedBefore> own = movedBefore.get(mixin);
        if (own == null) {
            return bytes;
        }
        ClassFile classFile = ClassFile.of();
        return classFile.transformClass(classFile.parse(bytes), (builder, element) -> {
            if (element instanceof MethodModel method
                    && own.stream()
                            .anyMatch(candidate -> method.methodName().equalsString(candidate.handler())
                                    && method.methodType().equalsString(candidate.descriptor()))) {
                builder.transformMethod(method, (methodBuilder, methodElement) -> {
                    if (methodElement instanceof RuntimeVisibleAnnotationsAttribute visible) {
                        methodBuilder.with(RuntimeVisibleAnnotationsAttribute.of(visible.annotations().stream()
                                .map(annotation -> annotation.className().equalsString(INJECT)
                                        ? withoutShift(annotation)
                                        : annotation)
                                .toList()));
                    } else {
                        methodBuilder.with(methodElement);
                    }
                });
            } else {
                builder.with(element);
            }
        });
    }

    /** {@code inject} with its {@code @At} points at the call itself (no {@code shift} or {@code by}). */
    private static Annotation withoutShift(Annotation inject) {
        return Annotation.of(
                inject.classSymbol(),
                inject.elements().stream()
                        .map(element -> element.name().equalsString("at")
                                ? AnnotationElement.of("at", withoutShift(element.value()))
                                : element)
                        .toList());
    }

    private static AnnotationValue withoutShift(AnnotationValue value) {
        if (value instanceof AnnotationValue.OfAnnotation point) {
            return AnnotationValue.ofAnnotation(Annotation.of(
                    point.annotation().classSymbol(),
                    point.annotation().elements().stream()
                            .filter(element -> !element.name().equalsString("shift")
                                    && !element.name().equalsString("by"))
                            .toList()));
        }
        if (value instanceof AnnotationValue.OfArray array) {
            return AnnotationValue.ofArray(array.values().stream()
                    .map(HostCapturedLocals::withoutShift)
                    .toList());
        }
        return value;
    }

    private static Annotation withoutLocals(Annotation inject) {
        return Annotation.of(
                inject.classSymbol(),
                inject.elements().stream()
                        .filter(element -> !element.name().equalsString("locals"))
                        .toList());
    }
}
