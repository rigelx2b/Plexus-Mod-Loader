package dev.plexusmodloader.compat.fabric.mixin;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.EnclosingMethodAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Anonymous game classes that the host's patch numbers differently from vanilla. javac numbers a class's anonymous
 * classes in the order they appear ({@code Outer$1}, {@code Outer$2}, ...), so a patch that adds one before vanilla's
 * shifts the names of the ones after it: MinecraftForge 26.2-65.1.5 and 26.3-66.1.0 add an {@code INBTBuilder} as
 * {@code CompoundTag$1}, and vanilla's {@code CompoundTag$1} (the {@code TagType} of compound tags, whose {@code
 * loadCompound} Lithium's {@code CompoundTagMixin$Type} targets) becomes {@code CompoundTag$2}. A mixin that names the
 * vanilla class would apply to the host's new one instead.
 *
 * <p>A mixin's target {@code Outer$N} is renumbered when the patched {@code Outer$N} is not the vanilla class ({@link
 * #same}: superclass, interfaces, enclosing method, fields and methods by name and descriptor), and exactly one patched
 * {@code Outer$M} is; the mixin's {@code @Mixin} then names {@code Outer$M} ({@code PLEXUS-COMPAT-226}), and {@link
 * HostPatchAdjustments.Classes#vanilla} answers for {@code Outer$M} with vanilla's {@code Outer$N}, so the rest of the
 * check compares the class with itself. A mixin that also names the class anywhere else (descriptors, code, injector
 * targets) is left as it is: only its {@code @Mixin} is rewritten.
 *
 * <p>Thread: confined to {@link HostPatchAdjustments#plan} while planning; {@link #apply} is read-only afterwards.
 */
final class HostRenumberedClasses {

    static final HostRenumberedClasses NONE = new HostRenumberedClasses(Map.of());

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final Pattern ANONYMOUS = Pattern.compile(".+\\$[0-9]+");
    /** How many anonymous classes of one outer class are compared at most. */
    private static final int MAX_ANONYMOUS = 256;

    /** Per mixin (internal name): each vanilla target name to the patched name of the same class. */
    private final Map<String, Map<String, String>> renamed;

    private HostRenumberedClasses(Map<String, Map<String, String>> renamed) {
        this.renamed = renamed;
    }

    /** Collects the renumbered targets while {@link HostPatchAdjustments#plan} goes through the mixins. */
    static final class Planner {

        private final HostPatchAdjustments.Classes classes;
        private final Map<String, Map<String, String>> renamed = new HashMap<>();

        Planner(HostPatchAdjustments.Classes classes) {
            this.classes = classes;
        }

        /**
         * The targets of {@code mixin} that the patch renumbered, each vanilla name to the patched one. Records them for
         * {@link #apply} and for the class lookups ({@link HostPatchAdjustments.Classes#renumber}); empty when none or
         * when the mixin names a renumbered class outside its {@code @Mixin}.
         */
        Map<String, String> check(ClassModel mixin) {
            Map<String, String> found = new LinkedHashMap<>();
            for (String target : HostPatchAdjustments.mixinTargets(mixin)) {
                patchedName(target).ifPresent(patched -> found.put(target, patched));
            }
            if (found.isEmpty() || found.keySet().stream().anyMatch(name -> namedOutsideMixinAnnotation(mixin, name))) {
                return Map.of();
            }
            found.forEach((vanilla, patched) -> classes.renumber(patched, vanilla));
            renamed.put(mixin.thisClass().asInternalName(), Map.copyOf(found));
            return found;
        }

        /** The patched name of the anonymous vanilla class {@code name}, when the patch numbered it differently. */
        private Optional<String> patchedName(String name) {
            if (!ANONYMOUS.matcher(name).matches()) {
                return Optional.empty();
            }
            Optional<ClassModel> vanilla = classes.vanillaAsNamed(name);
            Optional<ClassModel> sameName = classes.patched(name);
            if (vanilla.isEmpty() || sameName.isEmpty() || same(vanilla.get(), sameName.get())) {
                return Optional.empty();
            }
            String outer = name.substring(0, name.lastIndexOf('$'));
            List<String> matches = new ArrayList<>();
            for (int number = 1; number <= MAX_ANONYMOUS; number++) {
                String candidate = outer + "$" + number;
                Optional<ClassModel> patched = classes.patched(candidate);
                if (patched.isEmpty()) {
                    break;
                }
                if (!candidate.equals(name) && same(vanilla.get(), patched.get())) {
                    matches.add(candidate);
                }
            }
            return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
        }

        HostRenumberedClasses build() {
            return renamed.isEmpty() ? NONE : new HostRenumberedClasses(Map.copyOf(renamed));
        }
    }

    /**
     * Whether {@code vanilla} and {@code patched} are the same class under different names: the same superclass,
     * interfaces, enclosing class and method, and fields and methods by name and descriptor.
     */
    static boolean same(ClassModel vanilla, ClassModel patched) {
        return vanilla.superclass()
                        .map(entry -> entry.asInternalName())
                        .equals(patched.superclass().map(entry -> entry.asInternalName()))
                && interfaces(vanilla).equals(interfaces(patched))
                && enclosing(vanilla).equals(enclosing(patched))
                && members(vanilla).equals(members(patched));
    }

    private static Set<String> interfaces(ClassModel model) {
        Set<String> names = new TreeSet<>();
        model.interfaces().forEach(entry -> names.add(entry.asInternalName()));
        return names;
    }

    private static String enclosing(ClassModel model) {
        return model.findAttribute(java.lang.classfile.Attributes.enclosingMethod())
                .map(HostRenumberedClasses::describe)
                .orElse("");
    }

    private static String describe(EnclosingMethodAttribute attribute) {
        return attribute.enclosingClass().asInternalName()
                + attribute
                        .enclosingMethodName()
                        .map(name -> "." + name.stringValue())
                        .orElse("")
                + attribute
                        .enclosingMethodType()
                        .map(type -> type.stringValue())
                        .orElse("");
    }

    private static Set<String> members(ClassModel model) {
        Set<String> names = new TreeSet<>();
        for (FieldModel field : model.fields()) {
            names.add("F " + field.fieldName().stringValue() + " "
                    + field.fieldType().stringValue());
        }
        for (MethodModel method : model.methods()) {
            names.add("M " + method.methodName().stringValue()
                    + method.methodType().stringValue());
        }
        return names;
    }

    /**
     * Whether {@code mixin} names class {@code name} anywhere but in its {@code @Mixin}: in a field's or method's
     * descriptor, in code, or in a method's annotations (such as an injector's {@code @At} target).
     */
    static boolean namedOutsideMixinAnnotation(ClassModel mixin, String name) {
        String descriptor = "L" + name + ";";
        for (FieldModel field : mixin.fields()) {
            if (field.fieldType().stringValue().contains(descriptor)) {
                return true;
            }
        }
        for (MethodModel method : mixin.methods()) {
            if (method.methodType().stringValue().contains(descriptor)) {
                return true;
            }
            for (Attribute<?> attribute : method.attributes()) {
                List<Annotation> annotations = attribute instanceof RuntimeVisibleAnnotationsAttribute visible
                        ? visible.annotations()
                        : attribute instanceof RuntimeInvisibleAnnotationsAttribute invisible
                                ? invisible.annotations()
                                : List.of();
                for (Annotation annotation : annotations) {
                    if (mentions(AnnotationValue.ofAnnotation(annotation), name)) {
                        return true;
                    }
                }
            }
            if (method.code().isEmpty()) {
                continue;
            }
            for (CodeElement element : method.code().get()) {
                boolean names = switch (element) {
                    case FieldInstruction field ->
                        field.owner().asInternalName().equals(name)
                                || field.type().stringValue().contains(descriptor);
                    case InvokeInstruction call ->
                        call.owner().asInternalName().equals(name)
                                || call.type().stringValue().contains(descriptor);
                    case TypeCheckInstruction check ->
                        check.type().asInternalName().equals(name);
                    case NewObjectInstruction created ->
                        created.className().asInternalName().equals(name);
                    case InvokeDynamicInstruction dynamic ->
                        dynamic.typeSymbol().descriptorString().contains(descriptor);
                    case ConstantInstruction constant ->
                        constant.constantValue() instanceof ClassDesc type
                                && type.descriptorString().equals(descriptor);
                    default -> false;
                };
                if (names) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether an annotation value names class {@code name} (internal or dotted) in a string or class value. */
    private static boolean mentions(AnnotationValue value, String name) {
        return switch (value) {
            case AnnotationValue.OfString text ->
                text.stringValue().contains(name) || text.stringValue().contains(name.replace('/', '.'));
            case AnnotationValue.OfClass type -> type.className().stringValue().equals("L" + name + ";");
            case AnnotationValue.OfArray array -> array.values().stream().anyMatch(item -> mentions(item, name));
            case AnnotationValue.OfAnnotation nested ->
                nested.annotation().elements().stream().anyMatch(element -> mentions(element.value(), name));
            default -> false;
        };
    }

    /** {@code bytes} of mixin {@code mixin} with its {@code @Mixin} naming the renumbered targets by their patched names. */
    byte[] apply(String mixin, byte[] bytes) {
        Map<String, String> own = renamed.get(mixin);
        return own == null ? bytes : retarget(bytes, own);
    }

    /** {@code bytes} with the {@code @Mixin} targets in {@code renamed} (vanilla internal name to patched) replaced. */
    static byte[] retarget(byte[] bytes, Map<String, String> renamed) {
        ClassFile classFile = ClassFile.of();
        ClassModel model = classFile.parse(bytes);
        return classFile.transformClass(model, (builder, element) -> builder.with(retargeted(element, renamed)));
    }

    private static ClassElement retargeted(ClassElement element, Map<String, String> renamed) {
        if (element instanceof RuntimeInvisibleAnnotationsAttribute invisible) {
            return RuntimeInvisibleAnnotationsAttribute.of(retargeted(invisible.annotations(), renamed));
        }
        if (element instanceof RuntimeVisibleAnnotationsAttribute visible) {
            return RuntimeVisibleAnnotationsAttribute.of(retargeted(visible.annotations(), renamed));
        }
        return element;
    }

    private static List<Annotation> retargeted(List<Annotation> annotations, Map<String, String> renamed) {
        List<Annotation> result = new ArrayList<>();
        for (Annotation annotation : annotations) {
            if (!annotation.className().equalsString(MIXIN)) {
                result.add(annotation);
                continue;
            }
            List<AnnotationElement> elements = new ArrayList<>();
            for (AnnotationElement element : annotation.elements()) {
                elements.add(AnnotationElement.of(element.name().stringValue(), retargeted(element.value(), renamed)));
            }
            result.add(Annotation.of(annotation.classSymbol(), elements));
        }
        return result;
    }

    private static AnnotationValue retargeted(AnnotationValue value, Map<String, String> renamed) {
        if (value instanceof AnnotationValue.OfArray array) {
            return AnnotationValue.ofArray(array.values().stream()
                    .map(item -> retargeted(item, renamed))
                    .toList());
        }
        if (value instanceof AnnotationValue.OfString name) {
            String text = name.stringValue();
            boolean dotted = text.indexOf('.') >= 0;
            String patched = renamed.get(text.replace('.', '/'));
            if (patched != null) {
                return AnnotationValue.ofString(dotted ? patched.replace('/', '.') : patched);
            }
            return value;
        }
        if (value instanceof AnnotationValue.OfClass type) {
            String descriptor = type.className().stringValue();
            String patched = renamed.get(descriptor.substring(1, descriptor.length() - 1));
            if (patched != null) {
                return AnnotationValue.ofClass(ClassDesc.ofInternalName(patched));
            }
        }
        return value;
    }
}
