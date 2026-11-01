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
// Source: Fabric API (FabricMC), fabric-content-registries-v0 11.3.2+37b1aa249e, sources jar on maven.fabricmc.net,
// net/fabricmc/fabric/mixin/content/registry/AxeItemMixin.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.

package net.fabricmc.fabric.mixin.content.registry;

import com.llamalad7.mixinextras.sugar.Local;
import java.util.function.Function;
import net.fabricmc.fabric.api.registry.StrippableBlockRegistry;
import net.fabricmc.fabric.impl.content.registry.StrippableBlockRegistryImpl;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(AxeItem.class)
public class AxeItemMixin {
    @ModifyArg(
            method = "getStripped",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/util/Optional;map(Ljava/util/function/Function;)Ljava/util/Optional;"))
    private Function<Block, BlockState> handleCustomStrippingBehavior(
            Function<Block, BlockState> mapper, @Local(argsOnly = true) BlockState state) {
        StrippableBlockRegistry.StrippingTransformer transformer =
                StrippableBlockRegistryImpl.getTransformer(state.getBlock());

        if (transformer != null) {
            return block -> transformer.getStrippedBlockState(block, state);
        }

        return mapper;
    }
}
