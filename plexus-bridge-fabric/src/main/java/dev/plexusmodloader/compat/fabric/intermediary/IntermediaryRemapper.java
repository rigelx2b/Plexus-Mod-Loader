package dev.plexusmodloader.compat.fabric.intermediary;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;

/**
 * ASM's {@link Remapper} over an {@link IntermediaryTranslation}: every class, field and method name in a class file
 * that is an intermediary name becomes the Mojang name, in declarations (a mod class that overrides a game method, a
 * Mixin's {@code @Shadow} member) and in references, and so do strings that hold such names (annotation values, constants).
 *
 * <p>Records whether it changed anything, so that a class file without intermediary names is kept byte for byte.
 *
 * <p>Thread: one instance per class file; not thread-safe.
 */
final class IntermediaryRemapper extends Remapper {

    private final IntermediaryTranslation translation;
    private boolean changed;

    IntermediaryRemapper(IntermediaryTranslation translation) {
        super(Opcodes.ASM9);
        this.translation = translation;
    }

    /** Whether any name or string differs from the input so far. */
    boolean changed() {
        return changed;
    }

    /** Records a change made outside the mapping methods, such as a Mixin selector rewritten by the class visitor. */
    void noteChange(boolean differs) {
        if (differs) {
            changed = true;
        }
    }

    @Override
    public String map(String internalName) {
        String mapped = translation.mapClass(internalName);
        if (!mapped.equals(internalName)) {
            changed = true;
        }
        return mapped;
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        String mapped = translation.mapMethod(owner, name, descriptor);
        if (!mapped.equals(name)) {
            changed = true;
        }
        return mapped;
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        String mapped = translation.mapField(owner, name, descriptor);
        if (!mapped.equals(name)) {
            changed = true;
        }
        return mapped;
    }

    @Override
    public String mapRecordComponentName(String owner, String name, String descriptor) {
        String mapped = translation.mapField(owner, name, descriptor);
        if (!mapped.equals(name)) {
            changed = true;
        }
        return mapped;
    }

    @Override
    public Object mapValue(Object value) {
        if (value instanceof String text) {
            String mapped = translation.mapText(text);
            if (!mapped.equals(text)) {
                changed = true;
            }
            return mapped;
        }
        return super.mapValue(value);
    }
}
