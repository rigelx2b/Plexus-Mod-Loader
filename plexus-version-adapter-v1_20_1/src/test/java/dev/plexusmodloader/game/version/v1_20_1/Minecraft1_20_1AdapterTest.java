package dev.plexusmodloader.game.version.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.game.version.GameLaunchTarget;
import dev.plexusmodloader.game.version.GameVersionAdapter;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Minecraft1_20_1AdapterTest {

    @Test
    void isDiscoveredViaServiceLoader() {
        ServiceLoader<GameVersionAdapter> loader = ServiceLoader.load(GameVersionAdapter.class);
        boolean found = false;
        for (GameVersionAdapter adapter : loader) {
            if (adapter instanceof Minecraft1_20_1Adapter) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Minecraft1_20_1Adapter must be discoverable via ServiceLoader");
    }

    @Test
    void versionAndIdsMatch() {
        Minecraft1_20_1Adapter adapter = new Minecraft1_20_1Adapter();
        assertEquals("minecraft-1.20.1", adapter.adapterId());
        assertEquals("1.20.1", adapter.gameVersion());
        assertEquals(Set.of("1.20.1"), adapter.gameVersions());
    }

    @Test
    void launchTargetsResolveExpectedMainClasses() {
        Minecraft1_20_1Adapter adapter = new Minecraft1_20_1Adapter();
        assertEquals("net.minecraft.client.main.Main", adapter.mainClass(GameLaunchTarget.CLIENT));
        assertEquals("net.minecraft.server.Main", adapter.mainClass(GameLaunchTarget.DEDICATED_SERVER));
        assertEquals(
                "dev.plexusmodloader.game.layer.v1_20_1.gametest.GameTestMain",
                adapter.mainClass(GameLaunchTarget.GAME_TEST_SERVER));
    }

    /** Every client feature of the Native API has its rule: input, key bindings, tick, FOV, connection, HUD, payloads... */
    @Test
    void everyClientFeatureHasARuleThatRunsOnTheClientOnly() {
        Map<String, TransformRuleDefinition> rules = new HashMap<>();
        new Minecraft1_20_1Adapter().builtInTransformRules().forEach(rule -> rules.put(rule.ruleId(), rule));
        for (String id : List.of(
                "plexus:client_key_bindings",
                "plexus:client_key_input",
                "plexus:client_mouse_button",
                "plexus:client_mouse_scroll",
                "plexus:client_tick",
                "plexus:client_fov",
                "plexus:client_joined_world",
                "plexus:client_left_world",
                "plexus:client_hud",
                "plexus:client_payloads",
                "plexus:client_login_query",
                "plexus:client_menu_screens",
                "plexus:client_particle_providers",
                "plexus:client_entity_renderers",
                "plexus:client_block_entity_renderers",
                "plexus:client_asset_reload",
                "plexus:client_item_tooltip",
                "plexus:client_world_painters",
                "plexus:client_creative_page_buttons")) {
            TransformRuleDefinition rule = rules.get(id);
            assertNotNull(rule, id + " must exist");
            assertEquals(Set.of(GameSide.CLIENT), rule.sides(), id + " must run on the client only");
        }
    }

    @Test
    void builtInRulesAreValidAndUnique() {
        Minecraft1_20_1Adapter adapter = new Minecraft1_20_1Adapter();
        List<TransformRuleDefinition> rules = adapter.builtInTransformRules();
        assertFalse(rules.isEmpty(), "Rules should not be empty");

        Set<String> ruleIds = new HashSet<>();
        for (TransformRuleDefinition rule : rules) {
            assertNotNull(rule.ruleId(), "ruleId must not be null");
            assertTrue(rule.ruleId().startsWith("plexus:"), "ruleId must start with plexus: " + rule.ruleId());
            assertNotNull(rule.target(), "target must not be null: " + rule.ruleId());
            assertNotNull(rule.target().ownerInternalName(), "owner must not be null: " + rule.ruleId());
            assertNotNull(rule.target().name(), "name must not be null: " + rule.ruleId());
            assertNotNull(rule.target().descriptor(), "descriptor must not be null: " + rule.ruleId());
            assertNotNull(rule.action(), "action must not be null: " + rule.ruleId());
            assertNotNull(rule.failurePolicy(), "failurePolicy must not be null: " + rule.ruleId());
            assertTrue(ruleIds.add(rule.ruleId()), "Duplicate ruleId found: " + rule.ruleId());
        }
    }
}
