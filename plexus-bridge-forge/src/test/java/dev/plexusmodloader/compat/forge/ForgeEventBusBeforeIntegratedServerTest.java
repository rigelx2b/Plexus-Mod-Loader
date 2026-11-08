package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.internal.transform.InjectStaticCallAtHead;
import dev.plexusmodloader.internal.transform.MethodTarget;
import dev.plexusmodloader.internal.transform.TransformFailurePolicy;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import java.util.Set;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Forge's game event bus is started before a quick-play world's integrated server posts its events. */
class ForgeEventBusBeforeIntegratedServerTest {

    @AfterEach
    void reset() {
        ModLoader.valid = true;
    }

    @Test
    void theRuleCallsTheHookWhenTheIntegratedServerInitialisesOnTheClient() {
        TransformRuleDefinition rule = ForgeEventBusBeforeIntegratedServer.rule("1.21.1");

        assertEquals(Set.of(GameSide.CLIENT), rule.sides());
        assertEquals(
                new MethodTarget("net/minecraft/client/server/IntegratedServer", "initServer", "()Z"), rule.target());
        assertEquals(TransformFailurePolicy.DISABLE_RULE, rule.failurePolicy());
        assertEquals(
                new InjectStaticCallAtHead(
                        "dev/plexusmodloader/compat/forge/ForgeEventBusBeforeIntegratedServer",
                        "beforeIntegratedServer"),
                rule.action());
    }

    @Test
    void startsTheBusWhenForgesLoadingIsValid() {
        int before = MinecraftForge.STARTS.get();

        ForgeEventBusBeforeIntegratedServer.beforeIntegratedServer(this);

        assertEquals(before + 1, MinecraftForge.STARTS.get());
    }

    @Test
    void leavesTheBusShutWhenForgesLoadingFailed() {
        ModLoader.valid = false;
        int before = MinecraftForge.STARTS.get();

        ForgeEventBusBeforeIntegratedServer.beforeIntegratedServer(this);

        assertEquals(before, MinecraftForge.STARTS.get());
    }

    @Test
    void doesNothingWithoutForgesClasses() {
        int before = MinecraftForge.STARTS.get();

        // A class of the boot loader: Forge's classes are not found from it (Forge 1.21.11 and later have no EventBus
        // 6).
        ForgeEventBusBeforeIntegratedServer.beforeIntegratedServer("server");

        assertEquals(before, MinecraftForge.STARTS.get());
    }
}
