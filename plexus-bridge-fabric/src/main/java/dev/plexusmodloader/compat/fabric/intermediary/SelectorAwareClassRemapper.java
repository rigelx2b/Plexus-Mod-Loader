package dev.plexusmodloader.compat.fabric.intermediary;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
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
 * </ul>
 *
 * Everything else is {@link IntermediaryRemapper}'s.
 *
 * <p>Thread: one instance per class file; not thread-safe.
 */
final class SelectorAwareClassRemapper extends ClassRemapper {

    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    private final IntermediaryTranslation selectors;
    private final IntermediaryRemapper mapper;
    /** The name, as written in the input, of the method being visited. */
    private String methodName = "";

    SelectorAwareClassRemapper(ClassVisitor next, IntermediaryRemapper remapper, IntermediaryTranslation translation) {
        super(next, remapper);
        this.selectors = translation;
        this.mapper = remapper;
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        methodName = name;
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    @Override
    protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
        String method = methodName;
        return new MethodRemapper(api, methodVisitor, mapper) {
            @Override
            protected AnnotationVisitor createAnnotationRemapper(String descriptor, AnnotationVisitor visitor) {
                boolean accessor = ACCESSOR.equals(descriptor);
                boolean invoker = INVOKER.equals(descriptor);
                return new SelectorAnnotationRemapper(
                        api,
                        descriptor,
                        visitor,
                        mapper,
                        selectors,
                        false,
                        accessor || invoker ? inferredTarget(method, invoker) : null);
            }
        };
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
        /** What an accessor without a value would target, or {@code null}. */
        private final String inferred;

        private boolean hasValue;

        SelectorAnnotationRemapper(
                int api,
                String descriptor,
                AnnotationVisitor visitor,
                IntermediaryRemapper remapper,
                IntermediaryTranslation translation,
                boolean selectors,
                String inferred) {
            super(api, descriptor, visitor, remapper);
            this.remapper = remapper;
            this.translation = translation;
            this.selectors = selectors;
            this.inferred = inferred;
        }

        @Override
        public void visit(String name, Object value) {
            if ("value".equals(name)) {
                hasValue = true;
            }
            if (value instanceof String selector && (selectors || "method".equals(name))) {
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
                    api, null, array, remapper, translation, selectors || "method".equals(name), null);
        }

        @Override
        public void visitEnd() {
            if (inferred != null && !hasValue) {
                String mapped = translation.mapText(inferred);
                if (!mapped.equals(inferred)) {
                    remapper.noteChange(true);
                    super.visit("value", mapped);
                }
            }
            super.visitEnd();
        }
    }
}
