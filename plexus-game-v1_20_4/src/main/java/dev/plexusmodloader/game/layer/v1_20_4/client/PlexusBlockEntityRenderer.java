package dev.plexusmodloader.game.layer.v1_20_4.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.plexusmodloader.api.client.BlockEntityCanvas;
import dev.plexusmodloader.api.client.ShaderPipeline;
import dev.plexusmodloader.api.client.VertexSink;
import dev.plexusmodloader.api.content.block.state.BlockStateView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.game.layer.v1_20_4.Views;
import dev.plexusmodloader.game.layer.v1_20_4.content.BlockStates;
import dev.plexusmodloader.game.layer.v1_20_4.content.ModBlockEntity;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.BlockEntityRendererDeclaration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a mod's block entity painter in the game's block entity render call (on the render thread) and draws what it
 * asks for at once: 1.20.4 renders block entities immediately, without a separate render state.
 */
final class PlexusBlockEntityRenderer implements BlockEntityRenderer<BlockEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    private final BlockEntityRendererDeclaration declaration;
    private final ItemRenderer items;
    private final BlockRenderDispatcher blocks;
    private final Font font;
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicBoolean reported = new AtomicBoolean();

    PlexusBlockEntityRenderer(BlockEntityRendererProvider.Context context, BlockEntityRendererDeclaration declaration) {
        this.declaration = declaration;
        this.items = context.getItemRenderer();
        this.blocks = context.getBlockRenderDispatcher();
        this.font = context.getFont();
    }

    @Override
    public void render(
            BlockEntity blockEntity,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int light,
            int overlay) {
        if (failed.get() || !(blockEntity instanceof ModBlockEntity modEntity) || blockEntity.getLevel() == null) {
            return;
        }
        float time = blockEntity.getLevel().getGameTime() + partialTicks;
        int seed = (int) blockEntity.getBlockPos().asLong();
        int[] draws = {0};
        try {
            declaration.painter().paint(modEntity.view(), new BlockEntityCanvas() {
                @Override
                public void item(ResourceId item, double x, double y, double z, float scale, float yawDegrees) {
                    poseStack.pushPose();
                    poseStack.translate(x, y, z);
                    poseStack.mulPose(Axis.YP.rotationDegrees(yawDegrees));
                    poseStack.scale(scale, scale, scale);
                    items.renderStatic(
                            new ItemStack(Views.item(item)),
                            ItemDisplayContext.FIXED,
                            light,
                            overlay,
                            poseStack,
                            buffers,
                            blockEntity.getLevel(),
                            seed + draws[0]);
                    poseStack.popPose();
                    draws[0]++;
                }

                @Override
                public void text(String text, double x, double y, double z, float scale, int argb) {
                    Component line = Component.literal(text);
                    poseStack.pushPose();
                    poseStack.translate(x, y, z);
                    poseStack.mulPose(
                            Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
                    poseStack.scale(scale, -scale, scale);
                    font.drawInBatch(
                            line,
                            -font.width(line) / 2.0f,
                            0,
                            argb,
                            false,
                            poseStack.last().pose(),
                            buffers,
                            Font.DisplayMode.NORMAL,
                            0,
                            light);
                    poseStack.popPose();
                    draws[0]++;
                }

                @Override
                public void block(BlockStateView block, double x, double y, double z, float scale, float yawDegrees) {
                    poseStack.pushPose();
                    poseStack.translate(x, y, z);
                    poseStack.mulPose(Axis.YP.rotationDegrees(yawDegrees));
                    poseStack.scale(scale, scale, scale);
                    poseStack.translate(-0.5, -0.5, -0.5);
                    blocks.renderSingleBlock(BlockStates.gameState(block), poseStack, buffers, light, overlay);
                    poseStack.popPose();
                    draws[0]++;
                }

                @Override
                public BlockStateView defaultState(ResourceId block) {
                    return BlockStates.view(Views.block(block).defaultBlockState());
                }

                @Override
                public void quads(ShaderPipeline pipeline, Consumer<VertexSink> vertices) {
                    ModShaderPipelines.submit(
                            ModShaderPipelines.record(pipeline, vertices, 0, 0, 0), poseStack, buffers);
                    draws[0]++;
                }

                @Override
                public float animationTime() {
                    return time;
                }
            });
        } catch (RuntimeException | LinkageError e) {
            failed.set(true);
            LOGGER.error(
                    "[Plexus Mod Loader] PLEXUS-CLIENT-003 block entity renderer of {} ({}) failed and is disabled",
                    declaration.block(),
                    declaration.ownerModId(),
                    e);
            return;
        }
        if (draws[0] > 0 && reported.compareAndSet(false, true)) {
            LOGGER.info(
                    "[Plexus Mod Loader] Drew block entity renderer of {} ({} draw(s))", declaration.block(), draws[0]);
        }
    }
}
