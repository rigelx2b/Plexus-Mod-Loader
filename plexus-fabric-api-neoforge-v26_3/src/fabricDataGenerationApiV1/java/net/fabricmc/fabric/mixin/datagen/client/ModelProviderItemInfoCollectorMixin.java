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
// fabric-data-generation-api-v1/src/client/java/net/fabricmc/fabric/mixin/datagen/client/ModelProviderItemInfoCollectorMixin.java (Fabric API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.datagen.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.impl.datagen.client.FabricItemAssetDefinitions;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ModelProvider.ItemInfoCollector.class)
public class ModelProviderItemInfoCollectorMixin implements FabricItemAssetDefinitions {
    @Unique
    private FabricPackOutput fabricPackOutput;

    @Unique
    private Set<Block> processedBlocks;

    @Override
    public void fabric_setProcessedBlocks(Set<Block> processedBlocks) {
        this.processedBlocks = processedBlocks;
    }

    @Override
    public void setFabricPackOutput(FabricPackOutput fabricPackOutput) {
        this.fabricPackOutput = fabricPackOutput;
    }

    @WrapOperation(
            method = "lambda$finalizeAndValidate$0",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;containsKey(Ljava/lang/Object;)Z", ordinal = 1))
    private boolean filterItemsForProcessingMod(Map<Item, ClientItem> map, Object o, Operation<Boolean> original) {
        BlockItem blockItem = (BlockItem) o;

        if (fabricPackOutput != null) {
            // Only generate the item model if the block state json was registered
            if (!processedBlocks.contains(blockItem.getBlock())) {
                return true;
            }

            if (!BuiltInRegistries.ITEM.getKey(blockItem).getNamespace().equals(fabricPackOutput.getModId())) {
                // Skip over items that are not from the mod we are processing.
                return true;
            }
        }

        return original.call(map, o);
    }

    @ModifyArg(
            method = "finalizeAndValidate",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ljava/util/stream/Stream;filter(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;",
                            ordinal = 0))
    private Predicate<Holder.Reference<Item>> filterItemsForProcessingMod(Predicate<Holder.Reference<Item>> original) {
        if (fabricPackOutput != null) {
            return original.and(item -> fabricPackOutput.isStrictValidationEnabled())
                    // Skip over items that are not from the mod we are processing.
                    .and(item -> item.key().identifier().getNamespace().equals(fabricPackOutput.getModId()));
        }

        return original;
    }
}
