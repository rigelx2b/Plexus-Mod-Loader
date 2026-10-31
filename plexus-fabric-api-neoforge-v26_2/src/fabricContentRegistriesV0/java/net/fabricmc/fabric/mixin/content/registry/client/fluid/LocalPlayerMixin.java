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
// fabric-content-registries-v0/src/client/java/net/fabricmc/fabric/mixin/content/registry/client/fluid/LocalPlayerMixin.java (Fabric API's
// file as Forgified Fabric API has it).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.content.registry.client.fluid;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.fabricmc.fabric.impl.content.registry.fluid.EntityFluidInteractionRegistryImpl;
import net.fabricmc.fabric.impl.content.registry.fluid.InternalEntityFluidExtension;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
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
}
