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
// fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/impl/datagen/loot/FabricLootTableProviderImpl.java
// (Fabric API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.datagen.loot;

import com.google.common.collect.Maps;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricBlockLootSubProvider;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLootTableSubProvider;
import net.fabricmc.fabric.api.datagen.v1.provider.SimpleFabricLootTableSubProvider;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.impl.datagen.FabricDataGenHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.context.ContextKeySet;
import net.minecraft.world.level.storage.loot.LootTable;

public final class FabricLootTableProviderImpl {
    /**
     * Shared run logic for {@link FabricBlockLootSubProvider} and {@link SimpleFabricLootTableSubProvider}.
     */
    public static CompletableFuture<?> run(
            CachedOutput cache,
            FabricLootTableSubProvider provider,
            ContextKeySet contextParamSet,
            FabricPackOutput packOutput,
            CompletableFuture<HolderLookup.Provider> registryLookupFuture) {
        HashMap<Identifier, LootTable> builders = Maps.newHashMap();
        HashMap<Identifier, ResourceCondition[]> conditionMap = new HashMap<>();

        return registryLookupFuture.thenCompose(lookup -> {
            provider.generate((resourceKey, builder) -> {
                ResourceCondition[] conditions = FabricDataGenHelper.consumeConditions(builder);
                conditionMap.put(resourceKey.identifier(), conditions);

                if (builders.put(
                                resourceKey.identifier(),
                                builder.setParamSet(contextParamSet).build())
                        != null) {
                    throw new IllegalStateException("Duplicate loot table " + resourceKey.identifier());
                }
            });

            RegistryOps<JsonElement> ops = lookup.createSerializationContext(JsonOps.INSTANCE);
            final List<CompletableFuture<?>> futures = new ArrayList<>();

            for (Map.Entry<Identifier, LootTable> entry : builders.entrySet()) {
                JsonObject tableJson = (JsonObject) LootTable.DIRECT_CODEC
                        .encodeStart(ops, entry.getValue())
                        .getOrThrow(IllegalStateException::new);
                FabricDataGenHelper.addConditions(tableJson, conditionMap.remove(entry.getKey()));
                futures.add(DataProvider.saveStable(cache, tableJson, getOutputPath(packOutput, entry.getKey())));
            }

            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        });
    }

    private static Path getOutputPath(FabricPackOutput packOutput, Identifier lootTableId) {
        return packOutput
                .createRegistryElementsPathProvider(Registries.LOOT_TABLE)
                .json(lootTableId);
    }

    private FabricLootTableProviderImpl() {}
}
