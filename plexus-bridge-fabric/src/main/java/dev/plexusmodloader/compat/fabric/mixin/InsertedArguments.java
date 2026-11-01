package dev.plexusmodloader.compat.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Objects;

/**
 * The {@code Operation} a {@code @WrapOperation} handler gets when the host's patch makes, at the place of the wrapped
 * instance call, a static call that takes the receiver first, then arguments of its own, then the vanilla arguments
 * ({@link HostStaticCalls}): the handler calls it with the receiver and the vanilla arguments, and it calls the patched
 * call's {@code Operation} at that moment with the receiver, the host's own arguments as the patched game passed them,
 * and the vanilla ones. NeoForge calls {@code ClientHooks.extractScreen(screen, screenLayers, graphics, mouseX, mouseY,
 * partialTick)} where vanilla calls {@code screen.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY,
 * partialTick)}.
 *
 * <p>Thread: whichever thread runs the wrapped call; the object is used once, for that call.
 */
public final class InsertedArguments implements Operation<Object> {

    private final Operation<?> patched;
    private final Object[] inserted;

    /**
     * @param patched the patched call's {@code Operation}
     * @param inserted the arguments the patch passes after the receiver and before the vanilla ones, in order
     */
    public InsertedArguments(Operation<?> patched, Object[] inserted) {
        this.patched = Objects.requireNonNull(patched, "patched");
        this.inserted = inserted.clone();
    }

    /**
     * @param arguments the receiver followed by the vanilla arguments
     * @throws IllegalArgumentException when no receiver is given
     */
    @Override
    public Object call(Object... arguments) {
        if (arguments.length == 0) {
            throw new IllegalArgumentException("The wrapped call's receiver is missing");
        }
        Object[] all = new Object[arguments.length + inserted.length];
        all[0] = arguments[0];
        System.arraycopy(inserted, 0, all, 1, inserted.length);
        System.arraycopy(arguments, 1, all, 1 + inserted.length, arguments.length - 1);
        return patched.call(all);
    }
}
