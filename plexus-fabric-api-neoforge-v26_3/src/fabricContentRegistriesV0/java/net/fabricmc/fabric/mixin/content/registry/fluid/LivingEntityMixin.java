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
// fabric-content-registries-v0/src/main/java/net/fabricmc/fabric/mixin/content/registry/fluid/LivingEntityMixin.java
// (Fabric API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.content.registry.fluid;

import net.fabricmc.fabric.impl.content.registry.fluid.EntityFluidInteractionRegistryImpl;
import net.fabricmc.fabric.impl.content.registry.fluid.InternalEntityFluidExtension;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
    public LivingEntityMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Inject(method = "travelFlying(Lnet/minecraft/world/phys/Vec3;FFF)V", at = @At("HEAD"), cancellable = true)
    private void travelFlyingInCustomFluid(
            Vec3 input, float waterSpeed, float lavaSpeed, float airSpeed, CallbackInfo ci) {
        for (TagKey<Fluid> tagKey : ((InternalEntityFluidExtension) this).fabric_api$getTouchedCustomFluids()) {
            EntityFluidInteractionRegistryImpl.getFluidBehavior(tagKey)
                    .travelFlyingInFluid(tagKey, (LivingEntity) (Object) this, input, waterSpeed, lavaSpeed, airSpeed);
            ci.cancel();
        }
    }
}
