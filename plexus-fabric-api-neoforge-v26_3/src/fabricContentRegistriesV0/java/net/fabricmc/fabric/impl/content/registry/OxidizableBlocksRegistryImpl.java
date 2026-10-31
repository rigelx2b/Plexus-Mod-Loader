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
// fabric-content-registries-v0/src/main/java/net/fabricmc/fabric/impl/content/registry/OxidizableBlocksRegistryImpl.java (Fabric API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; also records what Fabric mods register, for NeoForge 26.3's data
// map lookups (DataMapModifications, DataMapHooksMixin).
package net.fabricmc.fabric.impl.content.registry;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.impl.item.BlockTransformerHelperImpl;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.WeatheringCopperCollection;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

public final class OxidizableBlocksRegistryImpl {
    // What Fabric mods registered, for NeoForge 26.3, which reads oxidation stages and waxed blocks from its data maps
    // only (DataMapModifications, DataMapHooksMixin).
    static final Map<Block, Block> NEXT_STAGES = new ConcurrentHashMap<>();
    static final Map<Block, Block> WAXED = new ConcurrentHashMap<>();

    private OxidizableBlocksRegistryImpl() {}

    public static void registerNextStage(Block from, Block to) {
        Objects.requireNonNull(from, "Oxidizable block cannot be null!");
        Objects.requireNonNull(to, "Oxidizable block cannot be null!");
        WeatheringCopper.NEXT_BY_BLOCK.get().put(from, to);
        NEXT_STAGES.put(from, to);
        // Fix #4371
        refreshRandomTickCache(from);
        refreshRandomTickCache(to);

        BlockTransformerHelperImpl.registerOxidationScraping(
                BlockPredicate.matchesBlocks(to), BlockStateProvider.of(from));
    }

    public static void registerWaxable(Block unwaxed, Block waxed) {
        Objects.requireNonNull(unwaxed, "Unwaxed block cannot be null!");
        Objects.requireNonNull(waxed, "Waxed block cannot be null!");
        HoneycombItem.WAXABLES.get().put(unwaxed, waxed);
        WAXED.put(unwaxed, waxed);

        BlockTransformerHelperImpl.registerWaxScraping(
                BlockPredicate.matchesBlocks(waxed), BlockStateProvider.of(unwaxed));
    }

    public static void registerWeatheringCopperBlocks(WeatheringCopperCollection<Block> copperBlocks) {
        Objects.requireNonNull(copperBlocks, "copperBlocks cannot be null!");
        copperBlocks.weathering().progressMapping(OxidizableBlocksRegistryImpl::registerNextStage);
        copperBlocks.zipUnwaxedWaxed(OxidizableBlocksRegistryImpl::registerWaxable);
    }

    private static void refreshRandomTickCache(Block block) {
        block.getStateDefinition()
                .getPossibleStates()
                .forEach(state -> ((RandomTickCacheRefresher) state).fabric_api$refreshRandomTickCache());
    }

    /** The previous stage of each block a Fabric mod registered as the next stage of another. */
    public static void addPreviousStages(Map<Block, Block> previousStages) {
        NEXT_STAGES.forEach((from, to) -> previousStages.put(to, from));
    }

    /** The unwaxed block of each block a Fabric mod registered as the waxed form of another. */
    public static void addUnwaxedBlocks(Map<Block, Block> unwaxedBlocks) {
        WAXED.forEach((unwaxed, waxed) -> unwaxedBlocks.put(waxed, unwaxed));
    }

    public interface RandomTickCacheRefresher {
        void fabric_api$refreshRandomTickCache();
    }
}
