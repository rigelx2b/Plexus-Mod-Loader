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
// Source: Fabric API (FabricMC), fabric-content-registries-v0 15.0.6+74ed1ea55d, sources jar on maven.fabricmc.net,
// net/fabricmc/fabric/mixin/content/registry/WalkNodeEvaluatorMixin.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.

package net.fabricmc.fabric.mixin.content.registry;

import com.llamalad7.mixinextras.sugar.Local;
import net.fabricmc.fabric.api.registry.LandPathTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Applied a bit earlier than other mods to ensure changes and optimizations to default vanilla behavior
@Mixin(value = WalkNodeEvaluator.class, priority = 999)
public class WalkNodeEvaluatorMixin {
    /**
     * Overrides the node type for the specified position, if the position is a direct target in a path.
     */
    @Inject(
            method = "getPathTypeFromState",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/state/BlockState;getBlock()Lnet/minecraft/world/level/block/Block;"),
            cancellable = true)
    private static void getCommonNodeType(
            BlockGetter level,
            BlockPos pos,
            CallbackInfoReturnable<PathType> cir,
            @Local(name = "blockState") BlockState blockState) {
        PathType pathType = LandPathTypeRegistry.getPathType(blockState, level, pos, false);

        if (pathType != null) {
            cir.setReturnValue(pathType);
        }
    }
}
