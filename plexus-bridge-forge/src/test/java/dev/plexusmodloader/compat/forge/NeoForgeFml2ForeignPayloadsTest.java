package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.internal.transform.HeadHookResult;
import dev.plexusmodloader.internal.transform.InvokeHookAtHead;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR-0026 40 on NeoForge 20.4: its channel checks disconnected the player on a Fabric payload no receiver took
 * ({@code Incompatible client! Please use NeoForge 20.4.253}); NeoForge's own payloads and its mods' registered
 * channels stay NeoForge's, everything else is another loader's.
 */
class NeoForgeFml2ForeignPayloadsTest {

    private static final Map<String, String> CONFIGURATION = Map.of("neoforge_hello:config", "registration");
    private static final Map<String, String> PLAY = Map.of("neoforge_hello:ping", "registration");

    @Test
    void neoForgesClassesAndItsModsChannelsAreNeoForgesAndTheRestIsForeign() {
        assertTrue(NeoForgeFml2ForeignPayloads.owns(
                "net.neoforged.neoforge.network.payload.MinecraftRegisterPayload",
                "minecraft:register",
                "PLAY",
                CONFIGURATION,
                PLAY));
        assertTrue(NeoForgeFml2ForeignPayloads.owns(
                "com.example.HelloPayload", "neoforge_hello:ping", "PLAY", CONFIGURATION, PLAY));
        assertTrue(NeoForgeFml2ForeignPayloads.owns(
                "com.example.HelloPayload", "neoforge_hello:config", "CONFIGURATION", CONFIGURATION, PLAY));

        assertFalse(NeoForgeFml2ForeignPayloads.owns(
                "com.example.HelloPayload", "neoforge_hello:ping", "CONFIGURATION", CONFIGURATION, PLAY));
        assertFalse(NeoForgeFml2ForeignPayloads.owns(
                "net.fabricmc.fabric.impl.networking.payload.UntypedPayload",
                "minecraft:register",
                "PLAY",
                CONFIGURATION,
                PLAY));
        assertFalse(NeoForgeFml2ForeignPayloads.owns(
                "net.fabricmc.fabric.impl.networking.payload.UntypedPayload",
                "voicechat:request_secret",
                "PLAY",
                CONFIGURATION,
                PLAY));
    }

    @Test
    void theRulesStartNeoForge20sFourChecksAndEndItsBundleFilterWithTheGameLayersHooks() {
        List<TransformRuleDefinition> rules =
                NeoForgeFml2ForeignPayloads.rules("1.20.4", "dev/plexusmodloader/game/layer/v1_20_4");

        assertEquals(5, rules.size());
        assertEquals(
                List.of(
                        "canSendPacket",
                        "canSendPacket",
                        "onModdedPacketAtServer",
                        "onModdedPacketAtClient",
                        "filterGameBundlePackets"),
                rules.stream().map(rule -> rule.target().name()).toList());
        assertTrue(rules.stream()
                .allMatch(rule ->
                        rule.action().describe().contains("dev.plexusmodloader.game.layer.v1_20_4.HostPayloadHooks")));
        assertEquals(
                List.of(
                        HeadHookResult.RETURN_IF_NON_NULL,
                        HeadHookResult.RETURN_IF_NON_NULL,
                        HeadHookResult.CANCEL_IF_TRUE,
                        HeadHookResult.RETURN_IF_NON_NULL),
                rules.stream()
                        .map(TransformRuleDefinition::action)
                        .filter(InvokeHookAtHead.class::isInstance)
                        .map(action -> ((InvokeHookAtHead) action).result())
                        .toList());
    }
}
