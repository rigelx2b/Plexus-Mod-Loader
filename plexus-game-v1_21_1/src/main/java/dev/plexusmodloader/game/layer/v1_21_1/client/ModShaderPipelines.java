package dev.plexusmodloader.game.layer.v1_21_1.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.plexusmodloader.api.client.ShaderPipeline;
import dev.plexusmodloader.api.client.ShaderPipelineDefinition;
import dev.plexusmodloader.api.client.ShaderUniform;
import dev.plexusmodloader.api.client.VertexSink;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.game.layer.v1_21_1.LayerState;
import dev.plexusmodloader.game.layer.v1_21_1.Views;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.ShaderPipelineDeclaration;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Render types with mods' core shaders, built on first use on the render thread from 1.21.1's {@link
 * ShaderInstance}. A pipeline whose shaders do not compile is reported once ({@code PLEXUS-CLIENT-006}) and its
 * quads are skipped.
 *
 * <p>1.21.1 loads a core shader from a JSON file next to its GLSL files; Plexus Mod Loader writes that JSON in
 * memory from the pipeline's definition, so a mod ships only {@code assets/<namespace>/shaders/core/<name>.vsh}
 * and {@code .fsh}. The game's own uniforms ({@code ModelViewMat}, {@code ProjMat}, {@code ColorModulator}, {@code
 * GameTime}, the fog values) are declared when the GLSL source uses them, and textures are {@code Sampler0}
 * onwards. 1.21.1 has no uniform blocks in its shader system: the mod's {@code ModUniforms} block is an OpenGL
 * uniform buffer that this class creates, binds to the program, and fills in the std140 layout once per frame.
 */
final class ModShaderPipelines {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    /** The uniform block name mod shaders declare. */
    static final String UNIFORM_BLOCK = "ModUniforms";

    /** The shader directory 1.21.1's {@code ShaderInstance} loads core shaders from. */
    private static final String CORE_DIRECTORY = "core/";

    /** The folder under {@code shaders/core/} that stands for a mod's shaders: see {@link #programName}. */
    private static final String MOD_PREFIX = "plexus_mod/";

    /** The uniform buffer binding point every mod block uses; each draw rebinds its own buffer to it. */
    private static final int UNIFORM_BINDING = 14;

    /** {@code GL_LEQUAL}. */
    private static final int DEPTH_LEQUAL = 515;

    /** The built pipeline per ID, or empty when it failed. */
    private static final Map<ResourceId, Optional<Built>> BUILT = new ConcurrentHashMap<>();

    private static final List<UniformBlock> UNIFORM_BLOCKS = new CopyOnWriteArrayList<>();

    private ModShaderPipelines() {}

    /**
     * One quad batch: absolute or block-local vertices, already relative to the pose's origin, with a
     * texture coordinate pair per vertex.
     */
    record Quads(ShaderPipeline pipeline, float[] positions, float[] uvs, int[] colors) {}

    private record Built(RenderType type, boolean textured) {}

    /** The std140 byte offset of each field of a uniform block and the block's size. */
    record Layout(int[] offsets, int size) {}

    /** A pipeline's uniform buffer and the last good values of its fields. */
    private static final class UniformBlock {

        final ResourceId id;
        final String ownerModId;
        final List<ShaderUniform> uniforms;
        final Layout layout;
        final int buffer;
        final ByteBuffer data;
        final float[][] values;
        volatile boolean failed;
        volatile boolean uploadReported;
        volatile boolean bindReported;
        final boolean[] valueReported;

        UniformBlock(ResourceId id, String ownerModId, List<ShaderUniform> uniforms, Layout layout, int buffer) {
            this.id = id;
            this.ownerModId = ownerModId;
            this.uniforms = uniforms;
            this.layout = layout;
            this.buffer = buffer;
            this.data = ByteBuffer.allocateDirect(layout.size()).order(ByteOrder.nativeOrder());
            this.values = new float[uniforms.size()][];
            this.valueReported = new boolean[uniforms.size()];
            for (int i = 0; i < uniforms.size(); i++) {
                values[i] = new float[uniforms.get(i).type().components()];
            }
        }
    }

    /**
     * The std140 layout of {@code uniforms} in declaration order: a {@code float} is 4 bytes aligned to 4, a
     * {@code vec2} 8 aligned to 8, a {@code vec3} 12 aligned to 16 and a {@code vec4} 16 aligned to 16; the
     * block is rounded up to a multiple of 16.
     */
    static Layout layout(List<ShaderUniform> uniforms) {
        int[] offsets = new int[uniforms.size()];
        int offset = 0;
        for (int i = 0; i < offsets.length; i++) {
            int alignment;
            int size;
            switch (uniforms.get(i).type()) {
                case FLOAT -> {
                    alignment = 4;
                    size = 4;
                }
                case VEC2 -> {
                    alignment = 8;
                    size = 8;
                }
                case VEC3 -> {
                    alignment = 16;
                    size = 12;
                }
                case VEC4 -> {
                    alignment = 16;
                    size = 16;
                }
                default ->
                    throw new IllegalStateException(
                            "Unknown uniform type " + uniforms.get(i).type());
            }
            offset = (offset + alignment - 1) / alignment * alignment;
            offsets[i] = offset;
            offset += size;
        }
        return new Layout(offsets, Math.max(16, (offset + 15) / 16 * 16));
    }

    /**
     * The JSON 1.21.1's {@code ShaderInstance} reads, written for a mod's shader: {@code shaderName} is the
     * shader's location without the {@code shaders/core/} directory or extension. {@code usedUniforms} are the
     * game uniforms the GLSL source mentions; declaring one the program lacks makes the game log a warning.
     */
    static String shaderJson(String shaderName, boolean textured, int textures, Set<String> usedUniforms) {
        StringBuilder json = new StringBuilder();
        json.append("{\"vertex\":\"")
                .append(shaderName)
                .append("\",\"fragment\":\"")
                .append(shaderName)
                .append("\",\"attributes\":[\"Position\",");
        if (textured) {
            json.append("\"UV0\",");
        }
        json.append("\"Color\"],\"samplers\":[");
        for (int i = 0; i < textures; i++) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"name\":\"")
                    .append(samplerName(i))
                    .append("\"}");
        }
        json.append("],\"uniforms\":[");
        List<String> entries = new ArrayList<>();
        String identity = "[1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0]";
        if (usedUniforms.contains("ModelViewMat")) {
            entries.add("{\"name\":\"ModelViewMat\",\"type\":\"matrix4x4\",\"count\":16,\"values\":" + identity + "}");
        }
        if (usedUniforms.contains("ProjMat")) {
            entries.add("{\"name\":\"ProjMat\",\"type\":\"matrix4x4\",\"count\":16,\"values\":" + identity + "}");
        }
        if (usedUniforms.contains("ColorModulator")) {
            entries.add("{\"name\":\"ColorModulator\",\"type\":\"float\",\"count\":4,\"values\":[1.0,1.0,1.0,1.0]}");
        }
        if (usedUniforms.contains("GameTime")) {
            entries.add("{\"name\":\"GameTime\",\"type\":\"float\",\"count\":1,\"values\":[0.0]}");
        }
        if (usedUniforms.contains("FogStart")) {
            entries.add("{\"name\":\"FogStart\",\"type\":\"float\",\"count\":1,\"values\":[0.0]}");
        }
        if (usedUniforms.contains("FogEnd")) {
            entries.add("{\"name\":\"FogEnd\",\"type\":\"float\",\"count\":1,\"values\":[1.0]}");
        }
        if (usedUniforms.contains("FogColor")) {
            entries.add("{\"name\":\"FogColor\",\"type\":\"float\",\"count\":4,\"values\":[0.0,0.0,0.0,0.0]}");
        }
        if (usedUniforms.contains("FogShape")) {
            entries.add("{\"name\":\"FogShape\",\"type\":\"int\",\"count\":1,\"values\":[0]}");
        }
        if (usedUniforms.contains("ScreenSize")) {
            entries.add("{\"name\":\"ScreenSize\",\"type\":\"float\",\"count\":2,\"values\":[1.0,1.0]}");
        }
        json.append(String.join(",", entries)).append("]}");
        return json.toString();
    }

    /** The game uniform names {@link #shaderJson} knows, found as whole words in {@code glsl}. */
    static Set<String> usedGameUniforms(String glsl) {
        Set<String> used = new LinkedHashSet<>();
        for (String name : List.of(
                "ModelViewMat",
                "ProjMat",
                "ColorModulator",
                "GameTime",
                "FogStart",
                "FogEnd",
                "FogColor",
                "FogShape",
                "ScreenSize")) {
            if (glsl.matches("(?s).*\\b" + name + "\\b.*")) {
                used.add(name);
            }
        }
        return used;
    }

    /** Records the vertices a mod writes, shifted by {@code -originX/Y/Z}. */
    static Quads record(
            ShaderPipeline pipeline, Consumer<VertexSink> vertices, double originX, double originY, double originZ) {
        List<float[]> positions = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        VertexSink sink = new VertexSink() {
            @Override
            public VertexSink vertex(double x, double y, double z, float u, float v, int argb) {
                positions.add(new float[] {(float) (x - originX), (float) (y - originY), (float) (z - originZ)});
                uvs.add(new float[] {u, v});
                colors.add(argb);
                return this;
            }
        };
        vertices.accept(sink);
        int complete = positions.size() - positions.size() % 4;
        float[] flatPositions = new float[complete * 3];
        float[] flatUvs = new float[complete * 2];
        int[] argb = new int[complete];
        for (int i = 0; i < complete; i++) {
            System.arraycopy(positions.get(i), 0, flatPositions, i * 3, 3);
            System.arraycopy(uvs.get(i), 0, flatUvs, i * 2, 2);
            argb[i] = colors.get(i);
        }
        return new Quads(pipeline, flatPositions, flatUvs, argb);
    }

    /** Writes {@code quads} with {@code poseStack}; skipped when the pipeline is unknown or broken. */
    static void submit(Quads quads, PoseStack poseStack, MultiBufferSource buffers) {
        if (quads.colors().length == 0) {
            return;
        }
        built(quads.pipeline().id()).ifPresent(built -> {
            VertexConsumer consumer = buffers.getBuffer(built.type());
            PoseStack.Pose pose = poseStack.last();
            float[] positions = quads.positions();
            float[] uvs = quads.uvs();
            int[] colors = quads.colors();
            for (int i = 0; i < colors.length; i++) {
                VertexConsumer vertex =
                        consumer.addVertex(pose, positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
                if (built.textured()) {
                    vertex = vertex.setUv(uvs[i * 2], uvs[i * 2 + 1]);
                }
                vertex.setColor(colors[i]);
            }
        });
    }

    /**
     * Writes this frame's mod uniform values into each pipeline's buffer. Called on the render thread at the
     * start of the level's rendering, outside any draw call.
     */
    static void uploadUniforms() {
        for (UniformBlock block : UNIFORM_BLOCKS) {
            if (block.failed) {
                continue;
            }
            try {
                for (int i = 0; i < block.uniforms.size(); i++) {
                    float[] value = currentValue(block, i);
                    int offset = block.layout.offsets()[i];
                    for (int c = 0; c < value.length; c++) {
                        block.data.putFloat(offset + c * 4, value[c]);
                    }
                }
                block.data.clear();
                GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, block.buffer);
                GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0L, block.data);
                GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
                if (!block.uploadReported) {
                    block.uploadReported = true;
                    LOGGER.info("[Plexus Mod Loader] Uploaded {} of mod shader pipeline {}", UNIFORM_BLOCK, block.id);
                }
            } catch (RuntimeException e) {
                block.failed = true;
                LOGGER.error(
                        "[Plexus Mod Loader] PLEXUS-CLIENT-007 cannot upload {} of shader pipeline {} of {}; its values stay as they were",
                        UNIFORM_BLOCK,
                        block.id,
                        block.ownerModId,
                        e);
            }
        }
    }

    /** The value of field {@code index} this frame, or its last good value after a bad one. */
    private static float[] currentValue(UniformBlock block, int index) {
        ShaderUniform uniform = block.uniforms.get(index);
        float[] value;
        try {
            value = uniform.source().values();
        } catch (RuntimeException e) {
            reportValue(block, index, "threw " + e);
            return block.values[index];
        }
        if (value == null || value.length != uniform.type().components()) {
            reportValue(
                    block,
                    index,
                    "returned " + (value == null ? "null" : value.length + " value(s)") + " for a " + uniform.type()
                            + " (" + uniform.type().components() + " expected)");
            return block.values[index];
        }
        System.arraycopy(value, 0, block.values[index], 0, value.length);
        return value;
    }

    private static void reportValue(UniformBlock block, int index, String problem) {
        if (!block.valueReported[index]) {
            block.valueReported[index] = true;
            LOGGER.error(
                    "[Plexus Mod Loader] PLEXUS-CLIENT-007 uniform {} of shader pipeline {} of {} {}; its last good value is used",
                    block.uniforms.get(index).name(),
                    block.id,
                    block.ownerModId,
                    problem);
        }
    }

    private static Optional<Built> built(ResourceId id) {
        return BUILT.computeIfAbsent(id, ModShaderPipelines::create);
    }

    private static Optional<Built> create(ResourceId id) {
        ShaderPipelineDeclaration declaration = LayerState.context()
                .flatMap(context -> context.declarations().shaderPipelines().stream()
                        .filter(candidate -> candidate.id().equals(id))
                        .findFirst())
                .orElse(null);
        if (declaration == null) {
            LOGGER.error("[Plexus Mod Loader] PLEXUS-CLIENT-006 no shader pipeline {} is declared", id);
            return Optional.empty();
        }
        ShaderPipelineDefinition definition = declaration.definition();
        ResourceId shader = definition.shader();
        if (!shader.path().startsWith(CORE_DIRECTORY)) {
            LOGGER.error(
                    "[Plexus Mod Loader] PLEXUS-CLIENT-006 shader pipeline {} of {} names shader {}; 1.21.1 loads mod shaders only from assets/<namespace>/shaders/core/, so its location must start with core/",
                    id,
                    declaration.ownerModId(),
                    shader);
            return Optional.empty();
        }
        String shaderName = programName(shader);
        try {
            Minecraft minecraft = Minecraft.getInstance();
            ResourceProvider resources = minecraft.getResourceManager();
            String glsl = source(resources, shader, ".vsh") + "\n" + source(resources, shader, ".fsh");
            String json = shaderJson(
                    shaderName, definition.textured(), definition.textures().size(), usedGameUniforms(glsl));
            ResourceLocation jsonLocation =
                    ResourceLocation.withDefaultNamespace("shaders/core/" + shaderName + ".json");
            ResourceProvider provider = location -> location.equals(jsonLocation)
                    ? Optional.of(
                            new Resource(null, () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
                    : resources.getResource(resolve(location));
            VertexFormat format =
                    definition.textured() ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR;
            ShaderInstance instance = new ShaderInstance(provider, shaderName, format);
            UniformBlock block = null;
            if (!definition.uniforms().isEmpty()) {
                block = createUniformBlock(id, declaration.ownerModId(), definition, instance);
                if (block == null) {
                    instance.close();
                    return Optional.empty();
                }
            }
            RenderType type = renderType(id, definition, instance, block);
            if (block != null) {
                UNIFORM_BLOCKS.add(block);
            }
            if (definition.textured() || !definition.uniforms().isEmpty()) {
                LOGGER.info(
                        "[Plexus Mod Loader] Built mod shader pipeline {} ({}) with {} texture(s) and {} uniform(s)",
                        id,
                        definition.shader(),
                        definition.textures().size(),
                        definition.uniforms().size());
            } else {
                LOGGER.info("[Plexus Mod Loader] Built mod shader pipeline {} ({})", id, definition.shader());
            }
            return Optional.of(new Built(type, definition.textured()));
        } catch (IOException | RuntimeException e) {
            LOGGER.error(
                    "[Plexus Mod Loader] PLEXUS-CLIENT-006 shader pipeline {} of {} did not compile or cannot be built ({}.vsh/.fsh); its quads are skipped",
                    id,
                    declaration.ownerModId(),
                    definition.shader(),
                    e);
            return Optional.empty();
        }
    }

    /**
     * The name {@code ShaderInstance} gets for a mod's shader. It puts the name into a location without a namespace, so
     * the name cannot hold a {@code ':'}; {@link #resolve} maps it back to the mod's files.
     */
    static String programName(ResourceId shader) {
        return MOD_PREFIX + shader.namespace() + "/" + shader.path().substring(CORE_DIRECTORY.length());
    }

    /**
     * The file to read for a request: {@code shaders/core/plexus_mod/<namespace>/<name>} is {@code shaders/core/<name>} of
     * that namespace. Everything else, such as the game's includes, is read as it is.
     */
    static ResourceLocation resolve(ResourceLocation location) {
        String prefix = "shaders/core/" + MOD_PREFIX;
        if (location.getPath().startsWith(prefix)) {
            String file = location.getPath().substring(prefix.length());
            int slash = file.indexOf('/');
            if (slash > 0) {
                return ResourceLocation.fromNamespaceAndPath(
                        file.substring(0, slash), "shaders/core/" + file.substring(slash + 1));
            }
        }
        return location;
    }

    /** The text of one GLSL file of {@code shader}, or an exception naming the missing file. */
    private static String source(ResourceProvider resources, ResourceId shader, String extension) throws IOException {
        ResourceLocation location = Views.id(ResourceId.of(shader.namespace(), "shaders/" + shader.path() + extension));
        try (BufferedReader reader = resources.getResourceOrThrow(location).openAsReader()) {
            StringBuilder text = new StringBuilder();
            reader.lines().forEach(line -> text.append(line).append('\n'));
            return text.toString();
        }
    }

    /**
     * Creates the uniform buffer, binds the program's {@code ModUniforms} block to {@link #UNIFORM_BINDING}, and
     * checks that the block's size is the std140 size of the declared fields. Returns null (after logging
     * {@code PLEXUS-CLIENT-006}) when the program has no such block or its size differs.
     */
    private static UniformBlock createUniformBlock(
            ResourceId id, String ownerModId, ShaderPipelineDefinition definition, ShaderInstance instance) {
        int program = instance.getId();
        int index = GL31.glGetUniformBlockIndex(program, UNIFORM_BLOCK);
        if (index == GL31.GL_INVALID_INDEX) {
            LOGGER.error(
                    "[Plexus Mod Loader] PLEXUS-CLIENT-006 shader pipeline {} of {} declares {} uniform(s) but {} has no uniform block {}; its quads are skipped",
                    id,
                    ownerModId,
                    definition.uniforms().size(),
                    definition.shader(),
                    UNIFORM_BLOCK);
            return null;
        }
        Layout layout = layout(definition.uniforms());
        int actual = GL31.glGetActiveUniformBlocki(program, index, GL31.GL_UNIFORM_BLOCK_DATA_SIZE);
        if (actual != layout.size()) {
            LOGGER.error(
                    "[Plexus Mod Loader] PLEXUS-CLIENT-006 shader pipeline {} of {}: block {} in {} is {} bytes but the {} declared field(s) take {} bytes in std140; its quads are skipped",
                    id,
                    ownerModId,
                    UNIFORM_BLOCK,
                    definition.shader(),
                    actual,
                    definition.uniforms().size(),
                    layout.size());
            return null;
        }
        GL31.glUniformBlockBinding(program, index, UNIFORM_BINDING);
        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, layout.size(), GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        return new UniformBlock(id, ownerModId, definition.uniforms(), layout, buffer);
    }

    /** A render type that sets the program, textures and fixed-function state of {@code definition}. */
    private static RenderType renderType(
            ResourceId id, ShaderPipelineDefinition definition, ShaderInstance instance, UniformBlock block) {
        List<ResourceLocation> textures =
                definition.textures().stream().map(Views::id).toList();
        boolean translucent = definition.blend() != ShaderPipelineDefinition.Blend.OPAQUE;
        Runnable setup = () -> {
            RenderSystem.setShader(() -> instance);
            for (int i = 0; i < textures.size(); i++) {
                RenderSystem.setShaderTexture(i, textures.get(i));
            }
            switch (definition.blend()) {
                case OPAQUE -> RenderSystem.disableBlend();
                case TRANSLUCENT -> {
                    RenderSystem.enableBlend();
                    RenderSystem.blendFuncSeparate(
                            GlStateManager.SourceFactor.SRC_ALPHA,
                            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                            GlStateManager.SourceFactor.ONE,
                            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                }
                case ADDITIVE -> {
                    RenderSystem.enableBlend();
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
                }
            }
            if (definition.depthTest()) {
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(DEPTH_LEQUAL);
            } else {
                RenderSystem.disableDepthTest();
            }
            RenderSystem.depthMask(definition.depthWrite());
            if (definition.cull()) {
                RenderSystem.enableCull();
            } else {
                RenderSystem.disableCull();
            }
            if (block != null) {
                GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, UNIFORM_BINDING, block.buffer);
                if (!block.bindReported) {
                    block.bindReported = true;
                    LOGGER.info(
                            "[Plexus Mod Loader] Bound {} of mod shader pipeline {} ({} field(s), {} bytes)",
                            UNIFORM_BLOCK,
                            block.id,
                            block.uniforms.size(),
                            block.layout.size());
                }
            }
        };
        Runnable clear = () -> {
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(DEPTH_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
        };
        VertexFormat format =
                definition.textured() ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR;
        return new RenderType(
                id.toString(), format, VertexFormat.Mode.QUADS, 1536, false, translucent, setup, clear) {};
    }

    static String samplerName(int index) {
        return "Sampler" + index;
    }
}
