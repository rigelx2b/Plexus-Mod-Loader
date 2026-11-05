package dev.plexusmodloader.game.layer.v1_21_1.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.plexusmodloader.api.client.BlockEntityCanvas;
import dev.plexusmodloader.api.client.ShaderPipeline;
import dev.plexusmodloader.api.client.VertexSink;
import dev.plexusmodloader.api.content.block.state.BlockStateView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.game.layer.v1_21_1.Views;
import dev.plexusmodloader.game.layer.v1_21_1.content.BlockStates;
import dev.plexusmodloader.game.layer.v1_21_1.content.ModBlockEntity;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.BlockEntityRendererDeclaration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a mod's block entity painter while the game renders the block entity (on the render thread); what the
 * painter draws goes straight into the game's buffers.
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
            MultiBufferSource buffer,
            int packedLight,
            int packedOverlay) {
        if (failed.get() || !(blockEntity instanceof ModBlockEntity modEntity) || blockEntity.getLevel() == null) {
            return;
        }
        float time = blockEntity.getLevel().getGameTime() + partialTicks;
        int seed = (int) blockEntity.getBlockPos().asLong();
        AtomicInteger draws = new AtomicInteger();
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
                            packedLight,
                            OverlayTexture.NO_OVERLAY,
                            poseStack,
                            buffer,
                            blockEntity.getLevel(),
                            seed + draws.getAndIncrement());
                    poseStack.popPose();
                }

                @Override
                public void text(String text, double x, double y, double z, float scale, int argb) {
                    FormattedCharSequence line = Component.literal(text).getVisualOrderText();
                    poseStack.pushPose();
                    poseStack.translate(x, y, z);
                    poseStack.mulPose(
                            Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
                    poseStack.scale(scale, -scale, scale);
                    font.drawInBatch(
                            line,
                            -font.width(line) / 2.0f,
                            0.0f,
                            argb,
                            false,
                            poseStack.last().pose(),
                            buffer,
                            Font.DisplayMode.NORMAL,
                            0,
                            packedLight);
                    poseStack.popPose();
                    draws.incrementAndGet();
                }

                @Override
                public void block(BlockStateView block, double x, double y, double z, float scale, float yawDegrees) {
                    poseStack.pushPose();
                    poseStack.translate(x, y, z);
                    poseStack.mulPose(Axis.YP.rotationDegrees(yawDegrees));
                    poseStack.scale(scale, scale, scale);
                    poseStack.translate(-0.5, -0.5, -0.5);
                    blocks.renderSingleBlock(
                            BlockStates.gameState(block), poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY);
                    poseStack.popPose();
                    draws.incrementAndGet();
                }

                @Override
                public BlockStateView defaultState(ResourceId block) {
                    return BlockStates.view(Views.block(block).defaultBlockState());
                }

                @Override
                public void quads(ShaderPipeline pipeline, Consumer<VertexSink> vertices) {
                    ModShaderPipelines.submit(
                            ModShaderPipelines.record(pipeline, vertices, 0, 0, 0), poseStack, buffer);
                    draws.incrementAndGet();
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
        if (draws.get() > 0 && reported.compareAndSet(false, true)) {
            LOGGER.info(
                    "[Plexus Mod Loader] Drew block entity renderer of {} ({} draw(s))",
                    declaration.block(),
                    draws.get());
        }
    }
}
