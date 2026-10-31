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
// fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/api/datagen/v1/loot/FabricBlockLootSubProvider.java
// (Fabric API's
// file as Forgified Fabric API has it).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.datagen.v1.loot;

import com.google.common.base.Preconditions;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.impl.datagen.loot.ConditionBlockLootSubProvider;
import net.minecraft.data.loot.BlockLootSubProvider;

/**
 * Fabric-provided extensions for {@link BlockLootSubProvider}.
 *
 * <p>Note: This interface is automatically implemented via Mixin and interface injection.
 */
public interface FabricBlockLootSubProvider {
    /**
     * Return a new generator that applies the specified conditions to any loot table it receives,
     * and then forwards the loot tables to this generator.
     */
    default BlockLootSubProvider withConditions(ResourceCondition... conditions) {
        Preconditions.checkArgument(conditions.length > 0, "Must add at least one condition.");

        return new ConditionBlockLootSubProvider((BlockLootSubProvider) this, conditions);
    }
}
