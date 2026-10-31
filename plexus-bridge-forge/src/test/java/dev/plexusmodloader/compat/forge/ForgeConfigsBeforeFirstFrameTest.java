package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.internal.transform.MethodTarget;
import dev.plexusmodloader.internal.transform.RedirectCall;
import dev.plexusmodloader.internal.transform.TransformFailurePolicy;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** MinecraftForge's client draws its first frame after its mods' configs are loaded. */
class ForgeConfigsBeforeFirstFrameTest {

    @Test
    void theRuleRedirectsForgesLoadingOverlayCallInMinecraftsConstructorOnTheClient() {
        TransformRuleDefinition rule = ForgeConfigsBeforeFirstFrame.rule("26.3");

        assertEquals(Set.of(GameSide.CLIENT), rule.sides());
        assertEquals(
                new MethodTarget(
                        "net/minecraft/client/Minecraft", "<init>", "(Lnet/minecraft/client/main/GameConfig;)V"),
                rule.target());
        assertEquals(TransformFailurePolicy.DISABLE_RULE, rule.failurePolicy());
        assertEquals(List.of("plexus:mod_initialization_client"), rule.after());
        RedirectCall redirect = (RedirectCall) rule.action();
        assertEquals(
                new MethodTarget(
                        "net/minecraftforge/fml/loading/ImmediateWindowHandler",
                        "loadingOverlay",
                        "(Ljava/util/function/Supplier;Ljava/util/function/Supplier;Ljava/util/function/Consumer;Z)"
                                + "Ljava/util/function/Supplier;"),
                redirect.invoked());
        assertEquals("dev/plexusmodloader/compat/forge/ForgeConfigsBeforeFirstFrame", redirect.hookOwnerInternalName());
        assertEquals("loadingOverlay", redirect.hookMethodName());
    }

    @Test
    void theFrameWaitsUntilForgeHasLoadedTheConfigs() {
        AtomicInteger polls = new AtomicInteger();
        AtomicLong clock = new AtomicLong();

        String line = ForgeConfigsBeforeFirstFrame.await(
                () -> polls.incrementAndGet() > 3, () -> true, () -> clock.addAndGet(5_000_000), 1_000_000_000);

        assertEquals(4, polls.get());
        assertTrue(line.startsWith("The game's first frame waited "), line);
    }

    @Test
    void theFrameStopsWaitingWhenForgesLoadingFailed() {
        String line = ForgeConfigsBeforeFirstFrame.await(() -> false, () -> false, () -> 0, 1_000_000_000);

        assertTrue(line.startsWith("Forge's mod loading failed"), line);
    }

    @Test
    void theFrameStopsWaitingAtTheLimit() {
        AtomicLong clock = new AtomicLong();

        String line = ForgeConfigsBeforeFirstFrame.await(
                () -> false, () -> true, () -> clock.addAndGet(400_000_000), 1_000_000_000);

        assertTrue(line.startsWith("Forge's configs were not loaded after 1 s"), line);
    }
}
