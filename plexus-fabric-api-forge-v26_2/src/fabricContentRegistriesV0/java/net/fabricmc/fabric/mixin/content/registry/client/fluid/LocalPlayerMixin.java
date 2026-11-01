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
// net/fabricmc/fabric/mixin/content/registry/client/fluid/LocalPlayerMixin.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.

package net.fabricmc.fabric.mixin.content.registry.client.fluid;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.fabricmc.fabric.impl.content.registry.fluid.EntityFluidInteractionRegistryImpl;
import net.fabricmc.fabric.impl.content.registry.fluid.InternalEntityFluidExtension;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
    @ModifyExpressionValue(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isInWater()Z"))
    private boolean handleCustomDownSwimmableFluids(boolean original) {
        if (original) {
            return true;
        }

        for (TagKey<Fluid> tagKey : ((InternalEntityFluidExtension) this).fabric_api$getTouchedCustomFluids()) {
            if (EntityFluidInteractionRegistryImpl.getFluidBehavior(tagKey)
                    .canMoveDownInFluid(tagKey, (Entity) (Object) this)) {
                return true;
            }
        }

        return false;
    }

    @ModifyExpressionValue(
            method = "shouldStopSwimSprinting",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isInWater()Z"))
    private boolean handleCustomSwimming(boolean original) {
        if (original) {
            return true;
        }

        for (TagKey<Fluid> tagKey : ((InternalEntityFluidExtension) this).fabric_api$getTouchedCustomFluids()) {
            if (EntityFluidInteractionRegistryImpl.getFluidBehavior(tagKey)
                    .canSwimInFluid(tagKey, (Entity) (Object) this)) {
                return true;
            }
        }

        return false;
    }

    @ModifyExpressionValue(
            method = "isSprintingPossible",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isInShallowWater()Z"))
    private boolean preventSprintingInFluid(boolean original) {
        if (original) {
            return true;
        }

        for (TagKey<Fluid> tagKey : ((InternalEntityFluidExtension) this).fabric_api$getTouchedCustomFluids()) {
            if (!EntityFluidInteractionRegistryImpl.getFluidBehavior(tagKey)
                    .canSprintInFluid(tagKey, (LivingEntity) (Object) this)) {
                return true;
            }
        }

        return false;
    }
}
