package dev.plexusmodloader.game.layer.v1_20_1.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.plexusmodloader.api.client.ClientEvents;
import dev.plexusmodloader.api.client.KeyAction;
import dev.plexusmodloader.api.client.ShaderPipelineDefinition;
import dev.plexusmodloader.api.client.ShaderUniform;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.game.layer.v1_20_1.LayerTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The client half's logic that needs no window: input values, program files, paths and tooltips. */
class ClientLogicTest {

    private static final LayerTestSupport.RecordingEvents EVENTS = new LayerTestSupport.RecordingEvents();

    @BeforeAll
    static void startClientLayer() {
        LayerTestSupport.bootstrap();
        LayerTestSupport.startLayer(LayerTestSupport.declarations(Map.of()), EVENTS, GameSide.CLIENT);
    }

    @Test
    void inputActionsAndButtonsMapToThePlexusValues() {
        assertEquals(Optional.of(KeyAction.PRESS), InputCodes.action(InputConstants.PRESS));
        assertEquals(Optional.of(KeyAction.RELEASE), InputCodes.action(InputConstants.RELEASE));
        assertEquals(Optional.of(KeyAction.REPEAT), InputCodes.action(InputConstants.REPEAT));
        assertEquals(Optional.empty(), InputCodes.action(99));
        assertEquals(InputConstants.KEY_F3, InputCodes.key(InputConstants.KEY_F3), "key codes are GLFW tokens");
        assertEquals(InputConstants.MOUSE_BUTTON_RIGHT, InputCodes.mouseButton(InputConstants.MOUSE_BUTTON_RIGHT));
        assertEquals(-1, InputCodes.mouseButton(-1));
    }

    @Test
    void theProgramFileNamesTheModsShaderSamplersAndUniforms() {
        ShaderPipelineDefinition definition = ShaderPipelineDefinition.builder(ResourceId.of("demo", "core/glow"))
                .blend(ShaderPipelineDefinition.Blend.TRANSLUCENT)
                .texture(ResourceId.of("demo", "textures/noise.png"))
                .texture(ResourceId.of("demo", "textures/mask.png"))
                .uniform("Strength", ShaderUniform.Type.FLOAT, () -> new float[] {1})
                .uniform("Tint", ShaderUniform.Type.VEC4, () -> new float[] {1, 1, 1, 1})
                .build();

        JsonObject program = JsonParser.parseString(ModShaderPipelines.programJson(definition))
                .getAsJsonObject();

        // The game reads shaders/core/<name>.vsh for this name; the provider maps it to shaders/core/glow.vsh.
        assertEquals("plexus_mod/demo/core/glow", program.get("vertex").getAsString());
        assertEquals("plexus_mod/demo/core/glow", program.get("fragment").getAsString());
        assertEquals("add", program.getAsJsonObject("blend").get("func").getAsString());
        assertEquals("srcalpha", program.getAsJsonObject("blend").get("srcrgb").getAsString());
        assertEquals(
                "1-srcalpha", program.getAsJsonObject("blend").get("dstrgb").getAsString());
        JsonArray samplers = program.getAsJsonArray("samplers");
        assertEquals(2, samplers.size());
        assertEquals("Sampler1", samplers.get(1).getAsJsonObject().get("name").getAsString());
        JsonArray uniforms = program.getAsJsonArray("uniforms");
        List<String> names = uniforms.asList().stream()
                .map(uniform -> uniform.getAsJsonObject().get("name").getAsString())
                .toList();
        assertEquals(List.of("ModelViewMat", "ProjMat", "ColorModulator", "GameTime", "Strength", "Tint"), names);
        JsonObject tint = uniforms.get(5).getAsJsonObject();
        assertEquals(4, tint.get("count").getAsInt());
        assertEquals(4, tint.getAsJsonArray("values").size());
    }

    @Test
    void blendModesAreWrittenAsTheGamesBlendNode() {
        JsonObject additive = JsonParser.parseString(ModShaderPipelines.programJson(
                        ShaderPipelineDefinition.builder(ResourceId.of("demo", "core/add"))
                                .blend(ShaderPipelineDefinition.Blend.ADDITIVE)
                                .build()))
                .getAsJsonObject();
        assertEquals("1", additive.getAsJsonObject("blend").get("dstrgb").getAsString());

        JsonObject opaque = JsonParser.parseString(ModShaderPipelines.programJson(
                        ShaderPipelineDefinition.builder(ResourceId.of("demo", "core/solid"))
                                .blend(ShaderPipelineDefinition.Blend.OPAQUE)
                                .build()))
                .getAsJsonObject();
        assertFalse(opaque.has("blend"), "an opaque shader leaves the game's blend off");
        assertEquals(0, opaque.getAsJsonArray("samplers").size());
    }

    @Test
    void aModUniformCannotTakeANameTheProgramDeclares() {
        ShaderPipelineDefinition definition = ShaderPipelineDefinition.builder(ResourceId.of("demo", "core/clash"))
                .uniform("ProjMat", ShaderUniform.Type.FLOAT, () -> new float[] {1})
                .build();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> ModShaderPipelines.programJson(definition));
    }

    @Test
    void aShaderNameUnderThePlexusFolderIsTheModsOwnFile() {
        assertEquals(
                new ResourceLocation("demo", "shaders/core/glow.vsh"),
                ModShaderPipelines.resolve(new ResourceLocation("shaders/core/plexus_mod/demo/core/glow.vsh")));
        ResourceLocation game = new ResourceLocation("shaders/include/fog.glsl");
        assertSame(game, ModShaderPipelines.resolve(game), "the game's own files are read as they are");
    }

    @Test
    void aPostEffectIsAChainFileUnderShadersPost() {
        ResourceId effect = ResourceId.of("demo", "sepia");
        ResourceLocation file = ClientHooks.postEffectFile(effect);
        assertEquals(new ResourceLocation("demo", "shaders/post/sepia.json"), file);
        assertEquals(Optional.of(effect), ClientHooks.postEffectId(file.toString()));
        assertEquals(Optional.empty(), ClientHooks.postEffectId("minecraft:other/file.json"));
    }

    @Test
    void tooltipListenersChangeACopyOfTheGamesLines() {
        List<Component> gameLines = List.of(Component.literal("Stick"));
        // Nobody listens yet: the game's list is returned as it is.
        assertSame(
                gameLines,
                ItemTooltips.onTooltipLines(new ItemStack(Items.STICK), null, TooltipFlag.Default.NORMAL, gameLines));

        EVENTS.listen(ClientEvents.ITEM_TOOLTIP, (stack, lines, advanced) -> {
            lines.add("Charged");
            lines.add(0, "[" + lines.get(0) + "]");
            lines.remove(1);
        });
        Player nobody = null;
        List<Component> changed =
                ItemTooltips.onTooltipLines(new ItemStack(Items.STICK), nobody, TooltipFlag.Default.NORMAL, gameLines);
        assertEquals(
                List.of("[Stick]", "Charged"),
                changed.stream().map(Component::getString).toList());
        assertEquals(1, gameLines.size(), "the game's own list is not changed");
    }

    @Test
    void theAdvancedFlagReachesTheTooltipListener() {
        boolean[] seen = {false};
        EVENTS.listen(ClientEvents.ITEM_TOOLTIP, (stack, lines, advanced) -> seen[0] = advanced);
        ItemTooltips.onTooltipLines(
                new ItemStack(Items.STICK), null, TooltipFlag.Default.ADVANCED, List.of(Component.literal("x")));
        assertTrue(seen[0]);
    }
}
