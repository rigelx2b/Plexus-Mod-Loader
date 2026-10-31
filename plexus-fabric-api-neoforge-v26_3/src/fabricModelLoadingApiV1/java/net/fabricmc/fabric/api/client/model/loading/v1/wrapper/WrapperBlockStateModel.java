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
// fabric-model-loading-api-v1/src/client/java/net/fabricmc/fabric/api/client/model/loading/v1/wrapper/WrapperBlockStateModel.java
// (Fabric API's file as Forgified Fabric API changed it for NeoForge).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.client.model.loading.v1.wrapper;

import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.BakedQuad.MaterialFlags;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A simple implementation of {@link BlockStateModel} that delegates all method calls to the {@link #wrapped} field.
 * Implementations must set the {@link #wrapped} field somehow.
 */
public abstract class WrapperBlockStateModel implements BlockStateModel {
    protected BlockStateModel wrapped;

    protected WrapperBlockStateModel() {}

    protected WrapperBlockStateModel(BlockStateModel wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        wrapped.collectParts(random, parts);
    }

    @Override
    public Material.Baked particleMaterial() {
        return wrapped.particleMaterial();
    }

    @Override
    public @BakedQuad.MaterialFlags int materialFlags() {
        return wrapped.materialFlags();
    }

    @Override
    public boolean hasMaterialFlag(@BakedQuad.MaterialFlags int flag) {
        return wrapped.hasMaterialFlag(flag);
    }

    @Override
    public void emitQuads(
            QuadEmitter emitter,
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            RandomSource random,
            Predicate<@Nullable Direction> cullTest) {
        wrapped.emitQuads(emitter, level, pos, state, random, cullTest);
    }

    @Override
    @Nullable
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return wrapped.createGeometryKey(level, pos, state, random);
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return wrapped.particleMaterial(level, pos, state);
    }

    @Override
    @BakedQuad.MaterialFlags
    public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return wrapped.materialFlags(level, pos, state, random);
    }

    @Override
    public boolean hasMaterialFlag(
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            RandomSource random,
            @BakedQuad.MaterialFlags int flag) {
        return wrapped.hasMaterialFlag(level, pos, state, random, flag);
    }

    @Override
    public void collectParts(
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            RandomSource random,
            List<BlockStateModelPart> parts) {
        wrapped.collectParts(level, pos, state, random, parts);
    }

    @Override
    public @MaterialFlags int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return wrapped.materialFlags(level, pos, state);
    }

    @Override
    public boolean hasMaterialFlag(BlockAndTintGetter level, BlockPos pos, BlockState state, @MaterialFlags int flag) {
        return wrapped.hasMaterialFlag(level, pos, state, flag);
    }
}
