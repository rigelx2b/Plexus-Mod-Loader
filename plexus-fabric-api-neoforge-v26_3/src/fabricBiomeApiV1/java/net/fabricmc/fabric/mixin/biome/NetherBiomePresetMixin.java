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
// Source: Fabric API (FabricMC), fabric-biome-api-v1 20.1.1+85210bca5d, sources jar on maven.fabricmc.net,
// net/fabricmc/fabric/mixin/biome/NetherBiomePresetMixin.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.

package net.fabricmc.fabric.mixin.biome;

import java.util.function.Function;
import net.fabricmc.fabric.impl.biome.NetherBiomeData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList$Preset$1")
public class NetherBiomePresetMixin {
    @Inject(method = "apply", at = @At("RETURN"), cancellable = true)
    public <T> void apply(
            Function<ResourceKey<Biome>, T> function, CallbackInfoReturnable<Climate.ParameterList<T>> cir) {
        cir.setReturnValue(NetherBiomeData.withModdedBiomeEntries(cir.getReturnValue(), function));
    }
}
