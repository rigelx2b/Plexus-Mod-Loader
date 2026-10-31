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
// fabric-renderer-indigo/src/client/java/net/fabricmc/fabric/impl/client/indigo/renderer/aocalc/AoLuminanceFix.java
// (Fabric API's file as Forgified Fabric API changed it for NeoForge).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.client.indigo.renderer.aocalc;

import net.fabricmc.fabric.impl.client.indigo.Indigo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Implements a fix to prevent luminous blocks from casting AO shade.
 * Will give normal result if fix is disabled.
 */
@FunctionalInterface
public interface AoLuminanceFix {
    float apply(BlockGetter level, BlockPos pos, BlockState state);

    AoLuminanceFix INSTANCE = Indigo.FIX_LUMINOUS_AO_SHADE ? AoLuminanceFix::fixed : AoLuminanceFix::vanilla;

    static float vanilla(BlockGetter level, BlockPos pos, BlockState state) {
        return state.getShadeBrightness(level, pos);
    }

    static float fixed(BlockGetter level, BlockPos pos, BlockState state) {
        return state.getLightEmission(level, pos) == 0 ? state.getShadeBrightness(level, pos) : 1f;
    }
}
