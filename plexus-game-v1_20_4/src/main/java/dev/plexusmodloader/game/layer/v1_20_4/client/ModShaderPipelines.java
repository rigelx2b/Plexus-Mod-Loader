package dev.plexusmodloader.game.layer.v1_20_4.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import dev.plexusmodloader.game.layer.v1_20_4.LayerState;
import dev.plexusmodloader.game.layer.v1_20_4.Views;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.ShaderPipelineDeclaration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Render types with mods' core shaders, built on first use on the render thread. 1.20.4 loads a core shader from a
 * program file {@code shaders/core/<name>.json} that names the vertex and fragment shader, the samplers, the uniforms
 * and the blend mode, and has no render pipelines; the program file is written here from the
 * {@link ShaderPipelineDefinition}, so a mod ships only its {@code .vsh} and {@code .fsh}. A pipeline whose shaders do
 * not compile is reported once ({@code PLEXUS-CLIENT-006}) and its quads are skipped.
 *
 * <p>The mod's shader files are the ones the definition names ({@code assets/<namespace>/shaders/<path>.vsh} and
 * {@code .fsh}) in the GLSL 1.50 core dialect of 1.20.4. The program declares the uniforms {@code ModelViewMat},
 * {@code ProjMat}, {@code ColorModulator} and {@code GameTime}, the samplers {@code Sampler0} onwards, and each mod
 * uniform by its own name as a {@code uniform float/vec2/vec3/vec4}; there is no {@code ModUniforms} block as in 26.x,
 * and no {@code #moj_import} of the uniform blocks.
 */
final class ModShaderPipelines {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    /** The folder under {@code shaders/core/} that stands for {@code shaders/}: see {@link #resolve}. */
    private static final String MOD_PREFIX = "plexus_mod/";

    private static final AtomicInteger PROGRAMS = new AtomicInteger();

    /** The uniforms every program declares: a mod uniform cannot use these names. */
    private static final java.util.Set<String> RESERVED_UNIFORMS =
            java.util.Set.of("ModelViewMat", "ProjMat", "ColorModulator", "GameTime");

    /** The built render type per ID, or empty when it failed. */
    private static final Map<ResourceId, Optional<Built>> BUILT = new ConcurrentHashMap<>();

    private ModShaderPipelines() {}

    /**
     * One quad batch: absolute or block-local vertices, already relative to the pose's origin, with a
     * texture coordinate pair per vertex.
     */
    record Quads(ShaderPipeline pipeline, float[] positions, float[] uvs, int[] colors) {}

    private record Built(RenderType type, boolean textured) {}

    /** The mod uniforms of one pipeline and the last good values of its fields. */
    private static final class Fields {

        final ResourceId id;
        final String ownerModId;
        final List<ShaderUniform> uniforms;
        final float[][] values;
        final boolean[] valueReported;
        volatile boolean failed;
        volatile boolean uploadReported;

        Fields(ResourceId id, String ownerModId, List<ShaderUniform> uniforms) {
            this.id = id;
            this.ownerModId = ownerModId;
            this.uniforms = uniforms;
            this.values = new float[uniforms.size()][];
            this.valueReported = new boolean[uniforms.size()];
            for (int i = 0; i < uniforms.size(); i++) {
                values[i] = new float[uniforms.get(i).type().components()];
            }
        }
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

    /** Writes {@code quads} with {@code poseStack}; skipped when the pipeline is unknown or broken. Render thread. */
    static void submit(Quads quads, PoseStack poseStack, MultiBufferSource buffers) {
        if (quads.colors().length == 0) {
            return;
        }
        Optional<Built> built = built(quads.pipeline().id());
        if (built.isEmpty()) {
            return;
        }
        Matrix4f pose = poseStack.last().pose();
        VertexConsumer buffer = buffers.getBuffer(built.get().type());
        float[] positions = quads.positions();
        float[] uvs = quads.uvs();
        int[] colors = quads.colors();
        for (int i = 0; i < colors.length; i++) {
            VertexConsumer vertex = buffer.vertex(pose, positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
            if (built.get().textured()) {
                vertex.uv(uvs[i * 2], uvs[i * 2 + 1]);
            }
            vertex.color((colors[i] >> 16) & 0xFF, (colors[i] >> 8) & 0xFF, colors[i] & 0xFF, (colors[i] >>> 24) & 0xFF)
                    .endVertex();
        }
    }

    /** The render type of a pipeline, so the caller can end its batch after submitting. */
    static Optional<RenderType> renderType(ResourceId id) {
        return built(id).map(Built::type);
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
        String program = "plexus_mod_" + PROGRAMS.incrementAndGet();
        try {
            ShaderInstance shader = new ShaderInstance(
                    new ProgramProvider(program, programJson(definition)),
                    program,
                    definition.textured()
                            ? DefaultVertexFormat.POSITION_TEX_COLOR
                            : DefaultVertexFormat.POSITION_COLOR);
            Fields fields = new Fields(id, declaration.ownerModId(), definition.uniforms());
            List<ResourceLocation> textures =
                    definition.textures().stream().map(Views::id).toList();
            RenderType type = new RenderType(
                    id.toString(),
                    definition.textured() ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    definition.blend() != ShaderPipelineDefinition.Blend.OPAQUE,
                    () -> setup(shader, fields, textures, definition),
                    () -> clear(definition)) {};
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
                    "[Plexus Mod Loader] PLEXUS-CLIENT-006 shader pipeline {} of {} did not compile or load ({}.vsh/.fsh);"
                            + " its quads are skipped",
                    id,
                    declaration.ownerModId(),
                    definition.shader(),
                    e);
            return Optional.empty();
        }
    }

    /** What runs before the render type draws: the shader, its textures, mod uniform values and the fixed state. */
    private static void setup(
            ShaderInstance shader,
            Fields fields,
            List<ResourceLocation> textures,
            ShaderPipelineDefinition definition) {
        RenderSystem.setShader(() -> shader);
        for (int i = 0; i < textures.size(); i++) {
            RenderSystem.setShaderTexture(i, textures.get(i));
        }
        if (definition.depthTest()) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(515);
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(definition.depthWrite());
        if (definition.cull()) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }
        if (!fields.failed) {
            try {
                for (int i = 0; i < fields.uniforms.size(); i++) {
                    shader.safeGetUniform(fields.uniforms.get(i).name()).set(currentValue(fields, i));
                }
                if (!fields.uploadReported && !fields.uniforms.isEmpty()) {
                    fields.uploadReported = true;
                    LOGGER.info(
                            "[Plexus Mod Loader] Uploaded {} uniform(s) of mod shader pipeline {}",
                            fields.uniforms.size(),
                            fields.id);
                }
            } catch (RuntimeException e) {
                fields.failed = true;
                LOGGER.error(
                        "[Plexus Mod Loader] PLEXUS-CLIENT-007 cannot upload the uniforms of shader pipeline {} of {};"
                                + " their values stay as they were",
                        fields.id,
                        fields.ownerModId,
                        e);
            }
        }
    }

    private static void clear(ShaderPipelineDefinition definition) {
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(515);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /** The value of field {@code index} this frame, or its last good value after a bad one. */
    private static float[] currentValue(Fields fields, int index) {
        ShaderUniform uniform = fields.uniforms.get(index);
        float[] value;
        try {
            value = uniform.source().values();
        } catch (RuntimeException e) {
            reportValue(fields, index, "threw " + e);
            return fields.values[index];
        }
        if (value == null || value.length != uniform.type().components()) {
            reportValue(
                    fields,
                    index,
                    "returned " + (value == null ? "null" : value.length + " value(s)") + " for a " + uniform.type()
                            + " (" + uniform.type().components() + " expected)");
            return fields.values[index];
        }
        System.arraycopy(value, 0, fields.values[index], 0, value.length);
        return value;
    }

    private static void reportValue(Fields fields, int index, String problem) {
        if (!fields.valueReported[index]) {
            fields.valueReported[index] = true;
            LOGGER.error(
                    "[Plexus Mod Loader] PLEXUS-CLIENT-007 uniform {} of shader pipeline {} of {} {}; its last good value is used",
                    fields.uniforms.get(index).name(),
                    fields.id,
                    fields.ownerModId,
                    problem);
        }
    }

    /**
     * The program file the game would read from {@code shaders/core/<name>.json}, written from the definition. The
     * shader names carry {@link #MOD_PREFIX}, which {@link ProgramProvider} maps back to the mod's own file path.
     */
    static String programJson(ShaderPipelineDefinition definition) {
        JsonObject program = new JsonObject();
        switch (definition.blend()) {
            case OPAQUE -> {}
            case TRANSLUCENT -> program.add("blend", blend("srcalpha", "1-srcalpha"));
            case ADDITIVE -> program.add("blend", blend("srcalpha", "1"));
        }
        // ShaderInstance puts this name into a location without a namespace, so it cannot hold a ':'.
        String shader = MOD_PREFIX + definition.shader().namespace() + "/"
                + definition.shader().path();
        program.addProperty("vertex", shader);
        program.addProperty("fragment", shader);
        JsonArray samplers = new JsonArray();
        for (int i = 0; i < definition.textures().size(); i++) {
            JsonObject sampler = new JsonObject();
            sampler.addProperty("name", "Sampler" + i);
            samplers.add(sampler);
        }
        program.add("samplers", samplers);
        JsonArray uniforms = new JsonArray();
        uniforms.add(uniform("ModelViewMat", "matrix4x4", identity()));
        uniforms.add(uniform("ProjMat", "matrix4x4", identity()));
        uniforms.add(uniform("ColorModulator", "float", new float[] {1, 1, 1, 1}));
        uniforms.add(uniform("GameTime", "float", new float[] {0}));
        for (ShaderUniform field : definition.uniforms()) {
            if (RESERVED_UNIFORMS.contains(field.name())) {
                throw new IllegalArgumentException("PLEXUS-CLIENT-006 uniform " + field.name() + " of shader pipeline "
                        + definition.shader() + " is one the program declares itself");
            }
            uniforms.add(uniform(field.name(), "float", new float[field.type().components()]));
        }
        program.add("uniforms", uniforms);
        return program.toString();
    }

    private static JsonObject blend(String source, String destination) {
        JsonObject blend = new JsonObject();
        blend.addProperty("func", "add");
        blend.addProperty("srcrgb", source);
        blend.addProperty("dstrgb", destination);
        return blend;
    }

    private static JsonObject uniform(String name, String type, float[] values) {
        JsonObject uniform = new JsonObject();
        uniform.addProperty("name", name);
        uniform.addProperty("type", type);
        uniform.addProperty("count", values.length);
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(value);
        }
        uniform.add("values", array);
        return uniform;
    }

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    /**
     * Where the shader files are read from. The game looks a core shader up as {@code shaders/core/<name>.json} and
     * {@code shaders/core/<shader>.vsh}; the program file is the one written above, and a shader name under {@link
     * #MOD_PREFIX} is the mod's file {@code shaders/<path>} (the mod names its shader without the {@code core/}
     * folder). Everything else, such as the game's includes, comes from the resource manager.
     */
    static final class ProgramProvider implements ResourceProvider {

        private final ResourceLocation programFile;
        private final String json;

        ProgramProvider(String program, String json) {
            this.programFile = new ResourceLocation("shaders/core/" + program + ".json");
            this.json = json;
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            return Minecraft.getInstance().getResourceManager().getResource(resolve(location));
        }

        @Override
        public BufferedReader openAsReader(ResourceLocation location) throws IOException {
            if (location.equals(programFile)) {
                return new BufferedReader(new StringReader(json));
            }
            return ResourceProvider.super.openAsReader(location);
        }
    }

    /**
     * The file to read for a request: {@code shaders/core/plexus_mod/<namespace>/<path>} is {@code shaders/<path>} of that
     * namespace.
     */
    static ResourceLocation resolve(ResourceLocation location) {
        String prefix = "shaders/core/" + MOD_PREFIX;
        if (location.getPath().startsWith(prefix)) {
            String file = location.getPath().substring(prefix.length());
            int slash = file.indexOf('/');
            if (slash > 0) {
                return new ResourceLocation(file.substring(0, slash), "shaders/" + file.substring(slash + 1));
            }
        }
        return location;
    }
}
