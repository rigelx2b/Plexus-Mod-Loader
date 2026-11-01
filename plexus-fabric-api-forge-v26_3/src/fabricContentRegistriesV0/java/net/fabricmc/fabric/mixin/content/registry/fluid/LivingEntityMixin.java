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
// net/fabricmc/fabric/mixin/content/registry/fluid/LivingEntityMixin.java.
// Plexus Mod Loader (ADR-0031): reformatted; without customFluidDrowning and swapFluidTag, whose targets MinecraftForge
// moved into its FluidType hooks (ForgeHooks.onLivingBreathe, jumpInFluid): ForgeFluidHooks drowns entities in custom
// fluids through Forge's LivingBreatheEvent, and Forge's lava jump (jumpInFluid) already ignores which fluid it is, as
// vanilla's jumpInLiquid ignores the tag.

package net.fabricmc.fabric.mixin.content.registry.fluid;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
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
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
    public LivingEntityMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @WrapWithCondition(
            method = "travelInFluid",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/LivingEntity;travelInLava(Lnet/minecraft/world/phys/Vec3;DZD)V"))
    private boolean travelInCustomFluid(
            LivingEntity instance, Vec3 vec3, double input, boolean baseGravity, double isFalling) {
        for (TagKey<Fluid> tagKey : ((InternalEntityFluidExtension) this).fabric_api$getTouchedCustomFluids()) {
            EntityFluidInteractionRegistryImpl.getFluidBehavior(tagKey)
                    .travelInFluid(tagKey, (LivingEntity) (Object) this, vec3, input, baseGravity, isFalling);
            return false;
        }

        return true;
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

    @Definition(id = "WATER", field = "Lnet/minecraft/tags/FluidTags;WATER:Lnet/minecraft/tags/TagKey;")
    @Definition(
            id = "getFluidHeight",
            method = "Lnet/minecraft/world/entity/LivingEntity;getFluidHeight(Lnet/minecraft/tags/TagKey;)D")
    @Expression("this.getFluidHeight(WATER)")
    @ModifyExpressionValue(method = "aiStep", at = @At("MIXINEXTRAS:EXPRESSION"))
    private double tryOtherFluidsForFluidJumping(double original, @Share("fluid") LocalRef<TagKey<Fluid>> fluid) {
        if (original != 0) {
            return original;
        }

        for (TagKey<Fluid> tagKey : ((InternalEntityFluidExtension) this).fabric_api$getTouchedCustomFluids()) {
            fluid.set(tagKey);
            return this.getFluidHeight(tagKey);
        }

        return 0;
    }

    // This looks to be a vanilla bug or some sort of leftover?
    // Causes fluids to apply their push twice if they aren't water (so in case of vanilla, lava only).
    // This is not desired effect for mods through, as it will cause push value to functionally double (and fluid update
    // stuff applying twice).
    // So I decided to just skip it for modded ones, while keeping vanilla/lava as is.
    @ModifyExpressionValue(
            method = "checkFallDamage",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isInWater()Z"))
    private boolean fixDoubleUpdateForCustomFluids(boolean original) {
        if (original) {
            return true;
        }

        return !((InternalEntityFluidExtension) this)
                .fabric_api$getTouchedCustomFluids()
                .isEmpty();
    }

    @ModifyExpressionValue(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isInLava()Z"),
            slice =
                    @Slice(
                            from =
                                    @At(
                                            value = "INVOKE",
                                            target =
                                                    "Lnet/minecraft/world/entity/LivingEntity;getFluidJumpThreshold()D")))
    private boolean jumpInCustomFluid(boolean original, @Share("fluid") LocalRef<TagKey<Fluid>> fluid) {
        return original || fluid.get() != null;
    }

    @ModifyArg(
            method = "aiStep",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/LivingEntity;isInShallowFluid(Lnet/minecraft/tags/TagKey;)Z"),
            slice =
                    @Slice(
                            from =
                                    @At(
                                            value = "INVOKE",
                                            target =
                                                    "Lnet/minecraft/world/entity/LivingEntity;getFluidJumpThreshold()D")))
    private TagKey<Fluid> swapFluidForShallowCheck(
            TagKey<Fluid> fluidTagKey, @Share("fluid") LocalRef<TagKey<Fluid>> fluid) {
        TagKey<Fluid> custom = fluid.get();
        return custom != null ? custom : fluidTagKey;
    }
}
