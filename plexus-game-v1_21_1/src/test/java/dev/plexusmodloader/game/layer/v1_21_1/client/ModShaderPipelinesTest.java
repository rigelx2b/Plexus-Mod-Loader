package dev.plexusmodloader.game.layer.v1_21_1.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.plexusmodloader.api.client.ShaderUniform;
import dev.plexusmodloader.api.resource.ResourceId;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ModShaderPipelinesTest {

    private static ShaderUniform uniform(String name, ShaderUniform.Type type) {
        return new ShaderUniform(name, type, () -> new float[type.components()]);
    }

    /** {@code ShaderInstance} puts the name into a location without a namespace, so it cannot hold a {@code ':'}. */
    @Test
    void aModsShaderIsNamedWithoutAColonAndMapsBackToItsFile() {
        String name = ModShaderPipelines.programName(ResourceId.of("demo", "core/glow"));
        assertEquals("plexus_mod/demo/glow", name);
        assertEquals(
                ResourceLocation.fromNamespaceAndPath("demo", "shaders/core/glow.vsh"),
                ModShaderPipelines.resolve(ResourceLocation.withDefaultNamespace("shaders/core/" + name + ".vsh")));
        ResourceLocation game = ResourceLocation.withDefaultNamespace("shaders/include/fog.glsl");
        assertEquals(game, ModShaderPipelines.resolve(game), "the game's own files are read as they are");
    }

    /** The native showcase mod's block: {@code float Strength; vec4 Tint;}. */
    @Test
    void std140AlignsAVec4To16() {
        ModShaderPipelines.Layout layout = ModShaderPipelines.layout(
                List.of(uniform("Strength", ShaderUniform.Type.FLOAT), uniform("Tint", ShaderUniform.Type.VEC4)));

        assertArrayEquals(new int[] {0, 16}, layout.offsets());
        assertEquals(32, layout.size());
    }

    @Test
    void std140PacksAFloatIntoTheTailOfAVec3() {
        ModShaderPipelines.Layout layout = ModShaderPipelines.layout(List.of(
                uniform("Direction", ShaderUniform.Type.VEC3),
                uniform("Strength", ShaderUniform.Type.FLOAT),
                uniform("Offset", ShaderUniform.Type.VEC2),
                uniform("Scale", ShaderUniform.Type.FLOAT)));

        assertArrayEquals(new int[] {0, 12, 16, 24}, layout.offsets());
        assertEquals(32, layout.size());
    }

    @Test
    void anEmptyBlockStillTakesOneVec4() {
        assertEquals(16, ModShaderPipelines.layout(List.of()).size());
    }

    @Test
    void gameUniformsAreFoundAsWholeWords() {
        Set<String> used = ModShaderPipelines.usedGameUniforms(
                "uniform mat4 ModelViewMat;\nuniform mat4 ProjMat;\nuniform vec4 ColorModulator;\n"
                        + "float t = GameTimeScale + MyFogStart;");

        assertTrue(used.contains("ModelViewMat"));
        assertTrue(used.contains("ProjMat"));
        assertTrue(used.contains("ColorModulator"));
        assertFalse(used.contains("GameTime"), "GameTimeScale is another name");
        assertFalse(used.contains("FogStart"), "MyFogStart is another name");
    }

    @Test
    void shaderJsonDeclaresSamplersAttributesAndUsedUniformsOnly() {
        String text = ModShaderPipelines.shaderJson("my_mod:glow", true, 2, Set.of("ModelViewMat", "ProjMat"));
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();

        assertEquals("my_mod:glow", json.get("vertex").getAsString());
        assertEquals("my_mod:glow", json.get("fragment").getAsString());
        assertEquals(
                List.of("Position", "UV0", "Color"),
                json.getAsJsonArray("attributes").asList().stream()
                        .map(element -> element.getAsString())
                        .toList());
        JsonArray samplers = json.getAsJsonArray("samplers");
        assertEquals("Sampler0", samplers.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Sampler1", samplers.get(1).getAsJsonObject().get("name").getAsString());
        JsonArray uniforms = json.getAsJsonArray("uniforms");
        assertEquals(2, uniforms.size());
        assertEquals(
                "ModelViewMat", uniforms.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(
                16, uniforms.get(0).getAsJsonObject().getAsJsonArray("values").size());
    }

    @Test
    void anUntexturedShaderHasNoUvAttributeAndNoSamplers() {
        JsonObject json = JsonParser.parseString(ModShaderPipelines.shaderJson("my_mod:flat", false, 0, Set.of()))
                .getAsJsonObject();

        assertEquals(2, json.getAsJsonArray("attributes").size());
        assertEquals(0, json.getAsJsonArray("samplers").size());
        assertEquals(0, json.getAsJsonArray("uniforms").size());
    }
}
