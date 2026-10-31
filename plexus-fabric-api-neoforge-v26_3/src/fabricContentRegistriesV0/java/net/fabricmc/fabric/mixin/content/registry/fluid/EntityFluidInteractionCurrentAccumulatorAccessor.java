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
// fabric-content-registries-v0/src/main/java/net/fabricmc/fabric/mixin/content/registry/fluid/EntityFluidInteractionTrackerAccessor.java (written
// in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; was EntityFluidInteractionTrackerAccessor: NeoForge 26.3
// accumulates currents in EntityFluidInteraction$CurrentAccumulator.
package net.fabricmc.fabric.mixin.content.registry.fluid;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.entity.EntityFluidInteraction$CurrentAccumulator")
public interface EntityFluidInteractionCurrentAccumulatorAccessor {
    @Accessor
    Vec3 getAccumulatedCurrent();

    @Accessor
    int getCurrentCount();
}
