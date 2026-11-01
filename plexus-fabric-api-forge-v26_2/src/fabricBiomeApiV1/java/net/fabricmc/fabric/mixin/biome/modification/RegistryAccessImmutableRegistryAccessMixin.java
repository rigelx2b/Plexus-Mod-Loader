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
// Source: Fabric API (FabricMC), fabric-biome-api-v1 18.0.6+c7bd5b8e9e, sources jar on maven.fabricmc.net,
// net/fabricmc/fabric/mixin/biome/modification/RegistryAccessImmutableRegistryAccessMixin.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.

package net.fabricmc.fabric.mixin.biome.modification;

import net.fabricmc.fabric.impl.biome.modification.BiomeModificationMarker;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * This Mixin allows us to keep backup copies of biomes for
 * {@link net.fabricmc.fabric.impl.biome.modification.BiomeModificationImpl} on a per-RegistryAccess basis.
 */
@Mixin(RegistryAccess.ImmutableRegistryAccess.class)
public class RegistryAccessImmutableRegistryAccessMixin implements BiomeModificationMarker {
    @Unique
    private boolean modified;

    @Override
    public void fabric_markModified() {
        if (modified) {
            throw new IllegalStateException("This dynamic registries instance has already been modified");
        }

        modified = true;
    }
}
