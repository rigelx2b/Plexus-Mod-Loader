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
// fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/mixin/datagen/loot/BlockLootSubProviderMixin.java
// (Fabric API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; without Fabric API 26.3's onlyVanillaBlocks (Forgified Fabric API's
// body): NeoForge 26.3's run iterates getKnownBlocks, which VanillaBlockLoot already limits to the minecraft
// namespace's blocks.
package net.fabricmc.fabric.mixin.datagen.loot;

import net.fabricmc.fabric.api.datagen.v1.loot.FabricBlockLootSubProvider;
import net.minecraft.data.loot.BlockLootSubProvider;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockLootSubProvider.class)
public class BlockLootSubProviderMixin implements FabricBlockLootSubProvider {}
