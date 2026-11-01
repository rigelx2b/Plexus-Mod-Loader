package dev.plexusmodloader.compat.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Objects;

/**
 * The {@code Operation} a {@code @WrapOperation} handler gets when the host's patch makes, at the place of the wrapped
 * instance call, a call of a method of the target class itself with the same arguments, which makes the vanilla call
 * ({@link HostTargetCalls}): the handler calls it with the vanilla receiver and arguments, and it calls the patched
 * call's {@code Operation} at that moment with the target instance in place of that receiver. MinecraftForge calls
 * {@code this.drawScreen(graphics, mouseX, mouseY, partialTick)} in {@code Gui.extractRenderState} where vanilla calls
 * {@code screen.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, partialTick)}.
 *
 * <p>Thread: whichever thread runs the wrapped call; the object is used once, for that call.
 */
public final class ReplacedReceiver implements Operation<Object> {

    private final Operation<?> patched;
    private final Object receiver;

    /**
     * @param patched the patched call's {@code Operation}
     * @param receiver the receiver of the patched call (the target instance)
     */
    public ReplacedReceiver(Operation<?> patched, Object receiver) {
        this.patched = Objects.requireNonNull(patched, "patched");
        this.receiver = Objects.requireNonNull(receiver, "receiver");
    }

    /**
     * @param arguments the vanilla receiver followed by the vanilla arguments
     * @throws IllegalArgumentException when no receiver is given
     */
    @Override
    public Object call(Object... arguments) {
        if (arguments.length == 0) {
            throw new IllegalArgumentException("The wrapped call's receiver is missing");
        }
        Object[] all = arguments.clone();
        all[0] = receiver;
        return patched.call(all);
    }
}
