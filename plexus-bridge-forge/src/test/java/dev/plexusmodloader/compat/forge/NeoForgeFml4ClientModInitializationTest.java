package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.internal.transform.InvokeHookAtCall;
import dev.plexusmodloader.internal.transform.MethodTarget;
import dev.plexusmodloader.internal.transform.TransformFailurePolicy;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** On NeoForge 21.1's client, other loaders' mods initialise after NeoForge has gathered and registered its mods. */
class NeoForgeFml4ClientModInitializationTest {

    @Test
    void theRuleRunsTheHookAfterClientModLoaderBeginsOnClientsAndStopsTheLaunchWhenItDoesNotApply() {
        TransformRuleDefinition rule = NeoForgeFml4ClientModInitialization.rule("1.21.1");

        assertEquals(Set.of(GameSide.CLIENT), rule.sides());
        assertEquals(
                new MethodTarget(
                        "net/neoforged/neoforge/client/loading/ClientModLoader",
                        "begin",
                        "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/repository/PackRepository;"
                                + "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V"),
                rule.target());
        assertEquals(TransformFailurePolicy.ABORT_STARTUP, rule.failurePolicy());
        InvokeHookAtCall hook = (InvokeHookAtCall) rule.action();
        assertEquals(
                new MethodTarget(
                        "net/neoforged/neoforge/client/loading/ClientModLoader", "begin", "(Ljava/lang/Runnable;Z)V"),
                hook.invoked());
        assertEquals(0, hook.ordinal());
        assertEquals(InvokeHookAtCall.Timing.AFTER, hook.timing());
        assertEquals(
                "dev/plexusmodloader/compat/forge/NeoForgeFml4ClientModInitialization", hook.hookOwnerInternalName());
        assertEquals("afterRegistration", hook.hookMethodName());
    }

    @Test
    void theDeferredInitializationRunsOnceAfterRegistration() {
        AtomicInteger runs = new AtomicInteger();
        NeoForgeFml4ClientModInitialization.defer(runs::incrementAndGet);

        NeoForgeFml4ClientModInitialization.afterRegistration(() -> {}, false);
        NeoForgeFml4ClientModInitialization.afterRegistration(() -> {}, false);

        assertEquals(1, runs.get());
    }

    @Test
    void onlyOneInitializationIsDeferred() {
        NeoForgeFml4ClientModInitialization.defer(() -> {});
        try {
            assertThrows(IllegalStateException.class, () -> NeoForgeFml4ClientModInitialization.defer(() -> {}));
        } finally {
            NeoForgeFml4ClientModInitialization.afterRegistration(() -> {}, false);
        }
    }
}
