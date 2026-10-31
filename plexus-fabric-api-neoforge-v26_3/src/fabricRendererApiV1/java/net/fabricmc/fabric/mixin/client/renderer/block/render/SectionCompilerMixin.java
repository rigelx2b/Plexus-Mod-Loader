/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Source: Sinytra Forgified Fabric API, branch 26.2.x, commit 40f8ce0,
// fabric-renderer-api-v1/src/client/java/net/fabricmc/fabric/mixin/client/renderer/block/render/SectionCompilerMixin.java
// (Fabric API's file as Forgified Fabric API changed it for NeoForge).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.client.renderer.block.render;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent.AdditionalSectionRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
    @Shadow
    @Final
    private boolean ambientOcclusion;

    @Shadow
    @Final
    private BlockColors blockColors;

    @Shadow
    protected abstract BufferBuilder getOrBeginLayer(
            Map<ChunkSectionLayer, BufferBuilder> startedLayers,
            SectionBufferBuilderPack buffers,
            ChunkSectionLayer layer);

    @Inject(
            method =
                    "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;"))
    private void beforeLoopCompile(
            SectionPos sectionPos,
            RenderSectionRegion region,
            VertexSorting vertexSorting,
            SectionBufferBuilderPack builders,
            List<AdditionalSectionRenderer> additionalRenderers,
            CallbackInfoReturnable<SectionCompiler.Results> cir,
            @Local(name = "startedLayers") Map<ChunkSectionLayer, BufferBuilder> startedLayers,
            @Share("altBlockRenderer") LocalRef<AltModelBlockRenderer> altBlockRenderer,
            @Share("altQuadOutput") LocalRef<QuadEmitter> altQuadOutput) {
        altBlockRenderer.set(Renderer.get().altModelBlockRenderer(ambientOcclusion, true, blockColors));
        altQuadOutput.set(Renderer.get().quadEmitter(quad -> {
            BufferBuilder builder = getOrBeginLayer(startedLayers, builders, quad.chunkLayer());
            quad.buffer(OverlayTexture.NO_OVERLAY, builder);
        }));
    }

    @Redirect(
            method =
                    "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "net/minecraft/client/renderer/block/ModelBlockRenderer.tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
    private void tesselateBlockProxy(
            ModelBlockRenderer blockRenderer,
            BlockQuadOutput output,
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState blockState,
            BlockStateModel model,
            long seed,
            @Share("altBlockRenderer") LocalRef<AltModelBlockRenderer> altBlockRenderer,
            @Share("altQuadOutput") LocalRef<QuadEmitter> altQuadOutput) {
        altBlockRenderer.get().tesselateBlock(altQuadOutput.get(), x, y, z, level, pos, blockState, model, seed);
    }
}
