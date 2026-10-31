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
// fabric-renderer-api-v1/src/client/java/net/fabricmc/fabric/mixin/client/renderer/block/render/BlockStateModelWrapperMixin.java
// (Fabric API's file, unchanged in Forgified Fabric API).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.client.renderer.block.render;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.BlockStateModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BlockStateModelWrapper.class)
abstract class BlockStateModelWrapperMixin implements BlockModel {
    @Shadow
    @Final
    private BlockStateModel model;

    @Shadow
    @Final
    private Matrix4fc transformation;

    @Shadow
    abstract void updateTints(BlockModelRenderState renderState, BlockState blockState);

    @Overwrite
    @Override
    public void update(
            BlockModelRenderState output, BlockState blockState, BlockDisplayContext displayContext, long seed) {
        // This could be optimized to inspect the quad output rather than calling hasMaterialFlag
        QuadEmitter emitter = output.setupMesh(
                transformation,
                model.hasMaterialFlag(
                        BlockAndTintGetter.EMPTY,
                        BlockPos.ZERO,
                        blockState,
                        output.scratchRandomSource(seed),
                        BakedQuad.FLAG_TRANSLUCENT));
        // TODO 26.1: somehow pass the level and pos here when available?
        model.emitQuads(
                emitter,
                BlockAndTintGetter.EMPTY,
                BlockPos.ZERO,
                blockState,
                output.scratchRandomSource(seed),
                _ -> false);
        updateTints(output, blockState);
    }
}
