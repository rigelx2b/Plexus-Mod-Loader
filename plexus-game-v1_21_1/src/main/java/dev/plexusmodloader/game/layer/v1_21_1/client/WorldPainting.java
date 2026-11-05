package dev.plexusmodloader.game.layer.v1_21_1.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.plexusmodloader.api.client.ShaderPipeline;
import dev.plexusmodloader.api.client.VertexSink;
import dev.plexusmodloader.api.client.WorldCanvas;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.WorldPainterDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs mods' world painters once per frame at the end of the level's rendering. 1.21.1 has no gizmo
 * collection, so lines, boxes, points and text are drawn here with the game's own line and colour shaders, and
 * shader quads with the mod's pipeline; everything is camera-relative under the level's model-view matrix.
 */
final class WorldPainting {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    /** {@code GL_LEQUAL}. */
    private static final int DEPTH_LEQUAL = 515;

    /** Half the edge, in blocks, of the cube a point of size 1 is drawn as. */
    private static final float POINT_HALF_EDGE_PER_SIZE = 0.0125f;

    private static final float TEXT_SCALE = 0.025f;

    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static final Map<Long, RenderType> LINE_TYPES = new ConcurrentHashMap<>();
    private static final RenderType FILL = fillType(false);
    private static final RenderType FILL_ON_TOP = fillType(true);

    private WorldPainting() {}

    private sealed interface Shape {}

    private record Line(
            double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width, boolean top)
            implements Shape {}

    private record Box(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            int strokeArgb,
            float width,
            int fillArgb,
            boolean top)
            implements Shape {}

    private record Point(double x, double y, double z, int argb, float size, boolean top) implements Shape {}

    private record Text(String text, double x, double y, double z, int argb, float scale, boolean top)
            implements Shape {}

    static void paint(List<WorldPainterDeclaration> painters, Camera camera, Matrix4f modelMatrix, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Vec3 position = camera.getPosition();
        float time = minecraft.level.getGameTime() + partialTick;
        List<Shape> shapes = new ArrayList<>();
        List<ModShaderPipelines.Quads> quads = new ArrayList<>();
        for (WorldPainterDeclaration painter : painters) {
            String key = painter.id().toString();
            if (FAILED.contains(key)) {
                continue;
            }
            int shapesBefore = shapes.size();
            int quadsBefore = quads.size();
            try {
                painter.painter().paint(new Canvas(position, time, false, shapes, quads));
            } catch (RuntimeException | LinkageError e) {
                FAILED.add(key);
                // Keep what earlier painters produced; drop the failed painter's partial output.
                shapes.subList(shapesBefore, shapes.size()).clear();
                quads.subList(quadsBefore, quads.size()).clear();
                LOGGER.error(
                        "[Plexus Mod Loader] PLEXUS-CLIENT-003 world painter {} ({}) failed and is disabled",
                        painter.id(),
                        painter.ownerModId(),
                        e);
                continue;
            }
            if (shapes.size() - shapesBefore + quads.size() - quadsBefore > 0 && REPORTED.add(key)) {
                LOGGER.info(
                        "[Plexus Mod Loader] World painter {} drew {} shape(s) and {} quad batch(es)",
                        painter.id(),
                        shapes.size() - shapesBefore,
                        quads.size() - quadsBefore);
            }
        }
        if (shapes.isEmpty() && quads.isEmpty()) {
            return;
        }
        MultiBufferSource.BufferSource source = minecraft.renderBuffers().bufferSource();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(modelMatrix);
        RenderSystem.applyModelViewMatrix();
        try {
            for (Shape shape : shapes) {
                draw(shape, position, camera, source, minecraft.font);
            }
            for (ModShaderPipelines.Quads batch : quads) {
                ModShaderPipelines.submit(batch, new PoseStack(), source);
            }
            source.endBatch();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void draw(Shape shape, Vec3 origin, Camera camera, MultiBufferSource source, Font font) {
        switch (shape) {
            case Line line ->
                line(
                        source.getBuffer(lineType(line.width(), line.top())),
                        line.x1() - origin.x,
                        line.y1() - origin.y,
                        line.z1() - origin.z,
                        line.x2() - origin.x,
                        line.y2() - origin.y,
                        line.z2() - origin.z,
                        line.argb());
            case Box box -> {
                float minX = (float) (box.minX() - origin.x);
                float minY = (float) (box.minY() - origin.y);
                float minZ = (float) (box.minZ() - origin.z);
                float maxX = (float) (box.maxX() - origin.x);
                float maxY = (float) (box.maxY() - origin.y);
                float maxZ = (float) (box.maxZ() - origin.z);
                if ((box.fillArgb() >>> 24) != 0) {
                    cube(
                            source.getBuffer(box.top() ? FILL_ON_TOP : FILL),
                            minX,
                            minY,
                            minZ,
                            maxX,
                            maxY,
                            maxZ,
                            box.fillArgb());
                }
                if ((box.strokeArgb() >>> 24) != 0) {
                    edges(
                            source.getBuffer(lineType(box.width(), box.top())),
                            minX,
                            minY,
                            minZ,
                            maxX,
                            maxY,
                            maxZ,
                            box.strokeArgb());
                }
            }
            case Point point -> {
                float half = point.size() * POINT_HALF_EDGE_PER_SIZE;
                float x = (float) (point.x() - origin.x);
                float y = (float) (point.y() - origin.y);
                float z = (float) (point.z() - origin.z);
                cube(
                        source.getBuffer(point.top() ? FILL_ON_TOP : FILL),
                        x - half,
                        y - half,
                        z - half,
                        x + half,
                        y + half,
                        z + half,
                        point.argb());
            }
            case Text text -> {
                PoseStack pose = new PoseStack();
                pose.translate(text.x() - origin.x, text.y() - origin.y, text.z() - origin.z);
                pose.mulPose(camera.rotation());
                float scale = TEXT_SCALE * text.scale();
                pose.scale(-scale, -scale, scale);
                font.drawInBatch(
                        Component.literal(text.text()),
                        -font.width(text.text()) / 2.0f,
                        0.0f,
                        text.argb(),
                        false,
                        pose.last().pose(),
                        source,
                        text.top() ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL,
                        0,
                        LightTexture.FULL_BRIGHT);
            }
        }
    }

    /** One line segment; the normal is its direction, which the lines shader widens it along. */
    private static void line(
            VertexConsumer consumer, double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length > 0.0f) {
            dx /= length;
            dy /= length;
            dz /= length;
        }
        consumer.addVertex((float) x1, (float) y1, (float) z1).setColor(argb).setNormal(dx, dy, dz);
        consumer.addVertex((float) x2, (float) y2, (float) z2).setColor(argb).setNormal(dx, dy, dz);
    }

    /** The twelve edges of a box. */
    private static void edges(
            VertexConsumer consumer, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int argb) {
        float[] xs = {minX, maxX};
        float[] ys = {minY, maxY};
        float[] zs = {minZ, maxZ};
        for (float y : ys) {
            for (float z : zs) {
                line(consumer, minX, y, z, maxX, y, z, argb);
            }
        }
        for (float x : xs) {
            for (float z : zs) {
                line(consumer, x, minY, z, x, maxY, z, argb);
            }
        }
        for (float x : xs) {
            for (float y : ys) {
                line(consumer, x, y, minZ, x, y, maxZ, argb);
            }
        }
    }

    /** The six faces of a box as quads. */
    private static void cube(VertexConsumer c, float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
        quad(c, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1); // down
        quad(c, argb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0); // up
        quad(c, argb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0); // north
        quad(c, argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1); // south
        quad(c, argb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0); // west
        quad(c, argb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1); // east
    }

    private static void quad(
            VertexConsumer c,
            int argb,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float cx,
            float cy,
            float cz,
            float dx,
            float dy,
            float dz) {
        c.addVertex(ax, ay, az).setColor(argb);
        c.addVertex(bx, by, bz).setColor(argb);
        c.addVertex(cx, cy, cz).setColor(argb);
        c.addVertex(dx, dy, dz).setColor(argb);
    }

    private static RenderType lineType(float width, boolean onTop) {
        long key = ((long) Float.floatToIntBits(width) << 1) | (onTop ? 1L : 0L);
        return LINE_TYPES.computeIfAbsent(key, ignored -> {
            Runnable setup = () -> {
                RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
                RenderSystem.lineWidth(width);
                state(onTop);
            };
            return new RenderType(
                    "plexus_world_lines",
                    DefaultVertexFormat.POSITION_COLOR_NORMAL,
                    VertexFormat.Mode.LINES,
                    1536,
                    false,
                    false,
                    setup,
                    WorldPainting::restore) {};
        });
    }

    private static RenderType fillType(boolean onTop) {
        Runnable setup = () -> {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            state(onTop);
        };
        return new RenderType(
                "plexus_world_fill",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                false,
                setup,
                WorldPainting::restore) {};
    }

    /** Translucent, double-sided, no depth writes; the depth test only when not drawing on top. */
    private static void state(boolean onTop) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (onTop) {
            RenderSystem.disableDepthTest();
        } else {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(DEPTH_LEQUAL);
        }
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
    }

    private static void restore() {
        RenderSystem.lineWidth(1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(DEPTH_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    private record Canvas(
            Vec3 camera, float time, boolean top, List<Shape> shapes, List<ModShaderPipelines.Quads> quads)
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

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width) {
            shapes.add(new Line(x1, y1, z1, x2, y2, z2, argb, width, top));
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
            shapes.add(new Box(minX, minY, minZ, maxX, maxY, maxZ, strokeArgb, width, fillArgb, top));
        }

        @Override
        public void point(double x, double y, double z, int argb, float size) {
            shapes.add(new Point(x, y, z, argb, size, top));
        }

        @Override
        public void text(String text, double x, double y, double z, int argb, float scale) {
            shapes.add(new Text(text, x, y, z, argb, scale, top));
        }

        @Override
        public void quads(ShaderPipeline pipeline, Consumer<VertexSink> vertices) {
            quads.add(ModShaderPipelines.record(pipeline, vertices, camera.x, camera.y, camera.z));
        }

        @Override
        public WorldCanvas onTop() {
            return new Canvas(camera, time, true, shapes, quads);
        }
    }
}
