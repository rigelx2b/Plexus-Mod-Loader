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
// fabric-content-registries-v0/src/main/java/net/fabricmc/fabric/api/registry/OxidizableBlocksRegistry.java (Fabric
// API's
// file as Forgified Fabric API has it).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.registry;

import net.fabricmc.fabric.impl.content.registry.OxidizableBlocksRegistryImpl;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopperCollection;

/**
 * Provides methods for registering oxidizable and waxable blocks.
 */
public final class OxidizableBlocksRegistry {
    private OxidizableBlocksRegistry() {}

    /**
     * Registers the next oxidization stage from one block to the other.
     *
     * @param from the variant with less oxidation
     * @param to the variant with more oxidation
     */
    public static void registerNextStage(Block from, Block to) {
        OxidizableBlocksRegistryImpl.registerNextStage(from, to);
    }

    /**
     * Registers a block pair as being able to add and remove wax.
     *
     * @param unwaxed the unwaxed variant
     * @param waxed   the waxed variant
     */
    public static void registerWaxable(Block unwaxed, Block waxed) {
        OxidizableBlocksRegistryImpl.registerWaxable(unwaxed, waxed);
    }

    /**
     * Registers a {@link WeatheringCopperCollection} and its oxidizing and waxing variants.
     *
     * @param copperBlocks the {@code WeatheringCopperCollection} to register
     */
    public static void registerWeatheringCopperBlocks(WeatheringCopperCollection<Block> copperBlocks) {
        OxidizableBlocksRegistryImpl.registerWeatheringCopperBlocks(copperBlocks);
    }
}
