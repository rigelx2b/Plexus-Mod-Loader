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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/mixin/client/rendering/ModelBlockRendererMixin.java (Fabric
// API's file, unchanged in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.client.rendering;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockTintsFactory;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
    @Shadow
    @Final
    private IntList computedTintValues;

    @Shadow
    @Final
    private List<@Nullable BlockTintSource> tintSources;

    @Inject(
            method =
                    "computeTintColor(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;I)I",
            at =
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tintSourcesInitialized:Z",
                            opcode = Opcodes.PUTFIELD,
                            shift = At.Shift.AFTER))
    private void injectFactoryTintCacheLoading(
            final BlockAndTintGetter level,
            final BlockState state,
            final BlockPos pos,
            final int tintIndex,
            final CallbackInfoReturnable<Integer> cir) {
        if (this.tintSources.isEmpty()) {
            final BlockTintsFactory factory = BlockColorRegistry.getFactory(state);

            if (factory != null) {
                factory.collect(state, level, pos, this.computedTintValues);
            }

            if (!this.computedTintValues.isEmpty()) {
                for (int i = 0; i < this.computedTintValues.size(); i++) {
                    this.tintSources.add(null);
                }
            }
        }
    }
}
