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
// fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/api/datagen/v1/provider/FabricLootTableSubProvider.java (Fabric API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.datagen.v1.provider;

import com.google.common.base.Preconditions;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.datagen.v1.loot.FabricEntityLootSubProvider;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.impl.datagen.FabricDataGenHelper;
import net.minecraft.data.DataProvider;
import net.minecraft.data.loot.LootTableSubProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.ApiStatus;

/**
 * A base interface for Loot table providers. You should not implement this class directly.
 *
 * <p>{@link FabricBlockLootSubProvider} provides additional features specific to block drop loot tables.
 *
 * <p>Use {@link SimpleFabricLootTableSubProvider} for a simple abstract class that you can implement to handle standard loot table functions.
 */
@ApiStatus.NonExtendable
public interface FabricLootTableSubProvider extends LootTableSubProvider, DataProvider {
    void generate(BiConsumer<ResourceKey<LootTable>, LootTable.Builder> biConsumer);

    /**
     * Return a new exporter that applies the specified conditions to any loot table it receives.
     *
     * <p>For block and entity loot tables, use {@link net.fabricmc.fabric.api.datagen.v1.loot.FabricBlockLootSubProvider#withConditions} or
     * {@link FabricEntityLootSubProvider#withConditions} instead, respectively.
     */
    default BiConsumer<ResourceKey<LootTable>, LootTable.Builder> withConditions(
            BiConsumer<ResourceKey<LootTable>, LootTable.Builder> exporter, ResourceCondition... conditions) {
        Preconditions.checkArgument(conditions.length > 0, "Must add at least one condition.");
        return (id, table) -> {
            FabricDataGenHelper.addConditions(table, conditions);
            exporter.accept(id, table);
        };
    }
}
