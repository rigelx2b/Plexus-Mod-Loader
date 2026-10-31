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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/mixin/client/rendering/GuiRendererMixin.java (Fabric API's
// file as Forgified Fabric API changed it for NeoForge).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.client.rendering;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
    @WrapOperation(
            method = "executeDraw",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/blaze3d/systems/RenderPass;setIndexBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;)V"))
    private void fixNonQuadIndexing(
            RenderPass instance,
            GpuBuffer buffer,
            IndexType indexType,
            Operation<Void> original,
            @Coerce GuiRendererDrawAccessor draw) {
        RenderPipeline pipeline = draw.fabric$pipeline();

        if (pipeline.usePipelineDrawModeForGui() && pipeline.getPrimitiveTopology() != PrimitiveTopology.QUADS) {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer =
                    RenderSystem.getSequentialBuffer(pipeline.getPrimitiveTopology());
            buffer = shapeIndexBuffer.getBuffer(
                    ((StagedVertexBufferDrawAccessor) draw.fabric$Draw()).fabric$indexCount());
            indexType = shapeIndexBuffer.type();
        }

        original.call(instance, buffer, indexType);
    }

    @ModifyExpressionValue(
            method = "addElementToMesh",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/render/GuiRenderer;scissorChanged(Lnet/minecraft/client/gui/navigation/ScreenRectangle;Lnet/minecraft/client/gui/navigation/ScreenRectangle;)Z"))
    private boolean uploadPrimitivesIndividually(boolean original, @Local RenderPipeline pipeline) {
        return original || pipeline.getPrimitiveTopology().connectedPrimitives;
    }
}
