package dev.plexusmodloader.game.layer.v1_20_4.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.plexusmodloader.api.client.ShaderPipeline;
import dev.plexusmodloader.api.client.VertexSink;
import dev.plexusmodloader.api.client.WorldCanvas;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.WorldPainterDeclaration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs mods' world painters once per frame at the end of the level render. 1.20.4 has no gizmos: lines, boxes, points
 * and text are drawn with render types built here (a line type per width, with and without the depth test), into the
 * game's buffer source, in world coordinates relative to the camera; shader quads use the mod pipeline's own type.
 */
final class WorldPainting {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    /** The game's nametag size (0.025 blocks per unit), which a text scale of 1 stands for. */
    private static final float TEXT_SCALE = 0.025f;

    /** Edge of a point per pixel of its size, in blocks. */
    private static final float POINT_PER_PIXEL = 1.0f / 32.0f;

    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Map<Float, RenderType> LINE_TYPES = new ConcurrentHashMap<>();
    private static final Map<Float, RenderType> LINE_TYPES_ON_TOP = new ConcurrentHashMap<>();
    private static RenderType filled;
    private static RenderType filledOnTop;

    private WorldPainting() {}

    static void paint(List<WorldPainterDeclaration> painters, PoseStack levelPose, Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Vec3 position = camera.getPosition();
        float time = minecraft.level.getGameTime() + minecraft.getFrameTime();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        for (WorldPainterDeclaration painter : painters) {
            String key = painter.id().toString();
            if (FAILED.contains(key)) {
                continue;
            }
            PoseStack pose = new PoseStack();
            pose.last().pose().set(levelPose.last().pose());
            pose.last().normal().set(levelPose.last().normal());
            pose.translate(-position.x, -position.y, -position.z);
            Canvas canvas = new Canvas(position, time, false, pose, buffers, camera, new int[] {0, 0});
            try {
                painter.painter().paint(canvas);
            } catch (RuntimeException | LinkageError e) {
                FAILED.add(key);
                LOGGER.error(
                        "[Plexus Mod Loader] PLEXUS-CLIENT-003 world painter {} ({}) failed and is disabled",
                        painter.id(),
                        painter.ownerModId(),
                        e);
                continue;
            } finally {
                buffers.endBatch();
            }
            if (canvas.counts[0] + canvas.counts[1] > 0 && REPORTED.add(key)) {
                LOGGER.info(
                        "[Plexus Mod Loader] World painter {} drew {} shape(s) and {} quad batch(es)",
                        painter.id(),
                        canvas.counts[0],
                        canvas.counts[1]);
            }
        }
    }

    /** The render type of lines {@code width} pixels wide, with or without the depth test. */
    static RenderType lines(float width, boolean onTop) {
        Map<Float, RenderType> types = onTop ? LINE_TYPES_ON_TOP : LINE_TYPES;
        return types.computeIfAbsent(
                width,
                w -> new RenderType(
                        "plexus_lines_" + w + (onTop ? "_on_top" : ""),
                        DefaultVertexFormat.POSITION_COLOR_NORMAL,
                        VertexFormat.Mode.LINES,
                        256,
                        false,
                        false,
                        () -> {
                            RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
                            RenderSystem.lineWidth(w);
                            RenderSystem.enableBlend();
                            RenderSystem.defaultBlendFunc();
                            RenderSystem.disableCull();
                            if (onTop) {
                                RenderSystem.disableDepthTest();
                            } else {
                                RenderSystem.enableDepthTest();
                                RenderSystem.depthFunc(515);
                            }
                        },
                        () -> {
                            RenderSystem.lineWidth(1.0f);
                            RenderSystem.enableCull();
                            RenderSystem.enableDepthTest();
                            RenderSystem.disableBlend();
                        }) {});
    }

    /** The render type of translucent filled boxes (a triangle strip of positions and colours). */
    private static synchronized RenderType filled(boolean onTop) {
        if (onTop ? filledOnTop == null : filled == null) {
            RenderType type = new RenderType(
                    "plexus_filled" + (onTop ? "_on_top" : ""),
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.TRIANGLE_STRIP,
                    256,
                    false,
                    true,
                    () -> {
                        RenderSystem.setShader(GameRenderer::getPositionColorShader);
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                        RenderSystem.disableCull();
                        RenderSystem.depthMask(false);
                        if (onTop) {
                            RenderSystem.disableDepthTest();
                        } else {
                            RenderSystem.enableDepthTest();
                            RenderSystem.depthFunc(515);
                        }
                    },
                    () -> {
                        RenderSystem.depthMask(true);
                        RenderSystem.enableCull();
                        RenderSystem.enableDepthTest();
                        RenderSystem.disableBlend();
                    }) {};
            if (onTop) {
                filledOnTop = type;
            } else {
                filled = type;
            }
        }
        return onTop ? filledOnTop : filled;
    }

    private record Canvas(
            Vec3 camera,
            float time,
            boolean alwaysOnTop,
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            Camera cameraView,
            int[] counts)
            implements WorldCanvas {

        @Override
        public double cameraX() {
            return camera.x;
        }

        @Override
        public double cameraY() {
            return camera.y;
        }

        @Override
        public double cameraZ() {
            return camera.z;
        }

        @Override
        public float animationTime() {
            return time;
        }

        private static float red(int argb) {
            return ((argb >> 16) & 0xFF) / 255.0f;
        }

        private static float green(int argb) {
            return ((argb >> 8) & 0xFF) / 255.0f;
        }

        private static float blue(int argb) {
            return (argb & 0xFF) / 255.0f;
        }

        private static float alpha(int argb) {
            return ((argb >>> 24) & 0xFF) / 255.0f;
        }

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width) {
            if (alpha(argb) == 0) {
                return;
            }
            VertexConsumer consumer = buffers.getBuffer(lines(width, alwaysOnTop));
            double dx = x2 - x1;
            double dy = y2 - y1;
            double dz = z2 - z1;
            double length = Math.max(Math.sqrt(dx * dx + dy * dy + dz * dz), 1.0e-6);
            float nx = (float) (dx / length);
            float ny = (float) (dy / length);
            float nz = (float) (dz / length);
            PoseStack.Pose last = pose.last();
            consumer.vertex(last.pose(), (float) x1, (float) y1, (float) z1)
                    .color(red(argb), green(argb), blue(argb), alpha(argb))
                    .normal(last.normal(), nx, ny, nz)
                    .endVertex();
            consumer.vertex(last.pose(), (float) x2, (float) y2, (float) z2)
                    .color(red(argb), green(argb), blue(argb), alpha(argb))
                    .normal(last.normal(), nx, ny, nz)
                    .endVertex();
            counts[0]++;
        }

        @Override
        public void box(
                double minX,
                double minY,
                double minZ,
                double maxX,
                double maxY,
                double maxZ,
                int strokeArgb,
                float width,
                int fillArgb) {
            if (alpha(fillArgb) > 0) {
                LevelRenderer.addChainedFilledBoxVertices(
                        pose,
                        buffers.getBuffer(filled(alwaysOnTop)),
                        minX,
                        minY,
                        minZ,
                        maxX,
                        maxY,
                        maxZ,
                        red(fillArgb),
                        green(fillArgb),
                        blue(fillArgb),
                        alpha(fillArgb));
            }
            if (alpha(strokeArgb) > 0) {
                LevelRenderer.renderLineBox(
                        pose,
                        buffers.getBuffer(lines(width, alwaysOnTop)),
                        minX,
                        minY,
                        minZ,
                        maxX,
                        maxY,
                        maxZ,
                        red(strokeArgb),
                        green(strokeArgb),
                        blue(strokeArgb),
                        alpha(strokeArgb));
            }
            // A box is one shape, with its fill, its outline or both.
            if (alpha(fillArgb) > 0 || alpha(strokeArgb) > 0) {
                counts[0]++;
            }
        }

        @Override
        public void point(double x, double y, double z, int argb, float size) {
            if (alpha(argb) == 0) {
                return;
            }
            double half = size * POINT_PER_PIXEL / 2.0;
            LevelRenderer.addChainedFilledBoxVertices(
                    pose,
                    buffers.getBuffer(filled(alwaysOnTop)),
                    x - half,
                    y - half,
                    z - half,
                    x + half,
                    y + half,
                    z + half,
                    red(argb),
                    green(argb),
                    blue(argb),
                    alpha(argb));
            counts[0]++;
        }

        @Override
        public void text(String text, double x, double y, double z, int argb, float scale) {
            if (alpha(argb) == 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            Component line = Component.literal(text);
            pose.pushPose();
            pose.translate(x, y, z);
            pose.mulPose(cameraView.rotation());
            pose.scale(TEXT_SCALE * scale, -TEXT_SCALE * scale, TEXT_SCALE * scale);
            font.drawInBatch(
                    line,
                    -font.width(line) / 2.0f,
                    0,
                    argb,
                    false,
                    pose.last().pose(),
                    buffers,
                    alwaysOnTop ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL,
                    0,
                    15728880);
            pose.popPose();
            counts[0]++;
        }

        @Override
        public void quads(ShaderPipeline pipeline, Consumer<VertexSink> vertices) {
            ModShaderPipelines.submit(
                    ModShaderPipelines.record(pipeline, vertices, camera.x, camera.y, camera.z),
                    relativePose(),
                    buffers);
            counts[1]++;
        }

        /** The quads are recorded relative to the camera, so they use the pose without its camera translation. */
        private PoseStack relativePose() {
            PoseStack relative = new PoseStack();
            relative.last().pose().set(pose.last().pose());
            relative.last().normal().set(pose.last().normal());
            relative.translate(camera.x, camera.y, camera.z);
            return relative;
        }

        @Override
        public WorldCanvas onTop() {
            return new Canvas(camera, time, true, pose, buffers, cameraView, counts);
        }
    }
}
