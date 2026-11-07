package dev.plexusmodloader.compat.fabric.intermediary;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnnotationRemapper;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;

/**
 * ASM's {@link ClassRemapper} with two Mixin conventions that a plain name remapper does not know (ADR-0037):
 *
 * <ul>
 *   <li>The {@code method} value of an injector annotation names one method by its intermediary name; in Mojang names
 *       the same name can belong to several overloads, which Mixin would all take for the target. A bare name whose
 *       method has a single descriptor gets that descriptor ({@link IntermediaryTranslation#mapMethodSelector}).
 *   <li>{@code @Accessor} and {@code @Invoker} without a value take their target from the accessor's own name
 *       ({@code getField_17086} reads {@code field_17086}). That name is the mod's, not the game's, so it stays, and the
 *       translated target is written as the annotation's {@code value}.
 *   <li>A string in a Mixin annotation that is a key of the class's reference map stays as the mod wrote it: Mixin
 *       looks it up there, and the reference map's values are translated ({@link ModJarTranslator}). Translating the
 *       key itself would leave the mod's development names (an {@code @At} target's owner) with no entry to look up.
 * </ul>
 *
 * Everything else is {@link IntermediaryRemapper}'s.
 *
 * <p>Thread: one instance per class file; not thread-safe.
 */
final class SelectorAwareClassRemapper extends ClassRemapper {

    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private final IntermediaryTranslation selectors;
    private final IntermediaryRemapper mapper;
    /** The keys of this class in the jar's Mixin reference maps. */
    private final Set<String> referenceKeys;
    /** The name, as written in the input, of the method being visited. */
    private String methodName = "";
    /** The descriptor, as written in the input, of the method being visited. */
    private String methodDescriptor = "()V";
    /** The classes the {@code @Mixin} annotation names, as written in the input. */
    private final List<String> mixinTargets = new ArrayList<>();

    SelectorAwareClassRemapper(
            ClassVisitor next,
            IntermediaryRemapper remapper,
            IntermediaryTranslation translation,
            Set<String> referenceKeys) {
        super(next, remapper);
        this.selectors = translation;
        this.mapper = remapper;
        this.referenceKeys = Set.copyOf(referenceKeys);
    }

    @Override
    protected AnnotationVisitor createAnnotationRemapper(String descriptor, AnnotationVisitor visitor) {
        return new SelectorAnnotationRemapper(
                api, descriptor, visitor, mapper, selectors, false, false, null, referenceKeys);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        AnnotationVisitor next = super.visitAnnotation(descriptor, visible);
        if (!MIXIN.equals(descriptor) || next == null) {
            return next;
        }
        // Records the Mixin's targets as written (intermediary names), ahead of the remapper.
        return new AnnotationVisitor(api, next) {
            @Override
            public AnnotationVisitor visitArray(String name) {
                AnnotationVisitor array = super.visitArray(name);
                if (!"value".equals(name) && !"targets".equals(name)) {
                    return array;
                }
                return new AnnotationVisitor(api, array) {
                    @Override
                    public void visit(String element, Object value) {
                        if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                            mixinTargets.add(type.getInternalName());
                        } else if (value instanceof String target) {
                            mixinTargets.add(target.replace('.', '/'));
                        }
                        super.visit(element, value);
                    }
                };
            }
        };
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        methodName = name;
        methodDescriptor = descriptor;
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    @Override
    protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
        String method = methodName;
        String descriptor = methodDescriptor;
        return new MethodRemapper(api, methodVisitor, mapper) {
            @Override
            protected AnnotationVisitor createAnnotationRemapper(String annotation, AnnotationVisitor visitor) {
                boolean accessor = ACCESSOR.equals(annotation);
                boolean invoker = INVOKER.equals(annotation);
                SelectorAnnotationRemapper remapper = new SelectorAnnotationRemapper(
                        api,
                        annotation,
                        visitor,
                        mapper,
                        selectors,
                        false,
                        true,
                        accessor || invoker ? inferredTarget(method, invoker) : null,
                        referenceKeys);
                if (accessor || invoker) {
                    remapper.accessorTarget = target -> accessorTarget(target, invoker, descriptor);
                }
                return remapper;
            }
        };
    }

    /**
     * An accessor's or invoker's target name: as {@link IntermediaryTranslation#mapText} translates it, or, when that
     * leaves it as written (an intermediary number reused in unrelated classes, {@code PLEXUS-COMPAT-145}), the member of
     * that name in one of the Mixin's targets, with the type the accessor reads or writes or the invoker's descriptor.
     */
    private String accessorTarget(String target, boolean invoker, String descriptor) {
        String mapped = selectors.mapText(target);
        if (!mapped.equals(target) || target.contains(";") || target.contains(":") || target.contains("(")) {
            return mapped;
        }
        Type method = Type.getMethodType(descriptor);
        String fieldType = method.getArgumentTypes().length == 0
                ? method.getReturnType().getDescriptor()
                : method.getArgumentTypes()[0].getDescriptor();
        for (String owner : mixinTargets) {
            String member = invoker
                    ? selectors.mapMethod(owner, target, descriptor)
                    : selectors.mapField(owner, target, fieldType);
            if (!member.equals(target)) {
                return member;
            }
        }
        return mapped;
    }

    /**
     * The target Mixin reads from an accessor's name when the annotation has no value: {@code get}, {@code set} or {@code
     * is} (accessor) or {@code call} or {@code invoke} (invoker), then the name with its first letter in lower case;
     * {@code null} when the name has no such shape.
     */
    static String inferredTarget(String methodName, boolean invoker) {
        for (String prefix : invoker ? new String[] {"call", "invoke"} : new String[] {"get", "set", "is"}) {
            if (methodName.length() > prefix.length()
                    && methodName.startsWith(prefix)
                    && Character.isUpperCase(methodName.charAt(prefix.length()))) {
                String rest = methodName.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return null;
    }

    /** Translates {@code method} attributes as selectors, in a single value or in an array. */
    private static final class SelectorAnnotationRemapper extends AnnotationRemapper {

        private final IntermediaryRemapper remapper;
        private final IntermediaryTranslation translation;
        private final boolean selectors;
        /** Whether a {@code method} attribute holds selectors (an injector on a method, not a nested annotation). */
        private final boolean methodAttribute;

        private final Set<String> referenceKeys;
        /** What an accessor without a value would target, or {@code null}. */
        private final String inferred;
        /** For an {@code @Accessor} or {@code @Invoker}: its target name translated in its Mixin's targets. */
        private UnaryOperator<String> accessorTarget;

        private boolean hasValue;

        SelectorAnnotationRemapper(
                int api,
                String descriptor,
                AnnotationVisitor visitor,
                IntermediaryRemapper remapper,
                IntermediaryTranslation translation,
                boolean selectors,
                boolean methodAttribute,
                String inferred,
                Set<String> referenceKeys) {
            super(api, descriptor, visitor, remapper);
            this.remapper = remapper;
            this.translation = translation;
            this.selectors = selectors;
            this.methodAttribute = methodAttribute;
            this.inferred = inferred;
            this.referenceKeys = referenceKeys;
        }

        @Override
        public void visit(String name, Object value) {
            if ("value".equals(name)) {
                hasValue = true;
            }
            if (value instanceof String key && referenceKeys.contains(key)) {
                if (av != null) {
                    av.visit(name, key);
                }
                return;
            }
            if (accessorTarget != null && "value".equals(name) && value instanceof String target) {
                String mapped = accessorTarget.apply(target);
                remapper.noteChange(!mapped.equals(target));
                super.visit(name, mapped);
                return;
            }
            if (value instanceof String selector && (selectors || (methodAttribute && "method".equals(name)))) {
                String mapped = translation.mapMethodSelector(selector);
                remapper.noteChange(!mapped.equals(selector));
                // The text has no intermediary names left, so the remapper leaves it as it is.
                super.visit(name, mapped);
                return;
            }
            super.visit(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if ("value".equals(name)) {
                hasValue = true;
            }
            AnnotationVisitor array = super.visitArray(name);
            if (array == null) {
                return null;
            }
            return new SelectorAnnotationRemapper(
                    api,
                    null,
                    array,
                    remapper,
                    translation,
                    selectors || (methodAttribute && "method".equals(name)),
                    false,
                    null,
                    referenceKeys);
        }

        @Override
        protected AnnotationVisitor createAnnotationRemapper(String descriptor, AnnotationVisitor visitor) {
            return new SelectorAnnotationRemapper(
                    api, descriptor, visitor, remapper, translation, false, false, null, referenceKeys);
        }

        @Override
        public void visitEnd() {
            if (inferred != null && !hasValue) {
                String mapped = accessorTarget != null ? accessorTarget.apply(inferred) : translation.mapText(inferred);
                if (!mapped.equals(inferred)) {
                    remapper.noteChange(true);
                    super.visit("value", mapped);
                }
            }
            super.visitEnd();
        }
    }
}
