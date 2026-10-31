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
// fabric-data-generation-api-v1/src/client/java/net/fabricmc/fabric/mixin/datagen/client/ModelProviderMixin.java
// (Fabric API's
// file as Forgified Fabric API has it).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.datagen.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.impl.datagen.client.FabricItemAssetDefinitions;
import net.fabricmc.fabric.impl.datagen.client.FabricModelProviderDefinitions;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.PackOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ModelProvider.class)
public class ModelProviderMixin {
    @Unique
    private FabricPackOutput fabricPackOutput;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void init(PackOutput output, CallbackInfo ci) {
        if (output instanceof FabricPackOutput fabricPackOutput) {
            this.fabricPackOutput = fabricPackOutput;
        }
    }

    @WrapOperation(
            method = "registerModels",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/data/models/BlockModelGenerators;run()V"))
    private void registerBlockStateModels(BlockModelGenerators instance, Operation<Void> original) {
        if (((Object) this) instanceof FabricModelProvider fabricModelProvider) {
            fabricModelProvider.generateBlockStateModels(instance);
        } else {
            // Fallback to the vanilla registration when not a fabric provider
            original.call(instance);
        }
    }

    @WrapOperation(
            method = "registerModels",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/data/models/ItemModelGenerators;run()V"))
    private void registerItemModels(ItemModelGenerators instance, Operation<Void> original) {
        if (((Object) this) instanceof FabricModelProvider fabricModelProvider) {
            fabricModelProvider.generateItemModels(instance);
        } else {
            // Fallback to the vanilla registration when not a fabric provider
            original.call(instance);
        }
    }

    @Inject(
            method = "run",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/data/models/ModelProvider;registerModels(Lnet/minecraft/client/data/models/BlockModelGenerators;Lnet/minecraft/client/data/models/ItemModelGenerators;)V"))
    private void setFabricPackOutput(
            CachedOutput output,
            CallbackInfoReturnable<CompletableFuture<?>> cir,
            @Local(name = "blockStateGenerators") ModelProvider.BlockStateGeneratorCollector blockStateGenerators,
            @Local(name = "itemModels") ModelProvider.ItemInfoCollector itemModels) {
        ((FabricModelProviderDefinitions) blockStateGenerators).setFabricPackOutput(fabricPackOutput);
        ((FabricModelProviderDefinitions) itemModels).setFabricPackOutput(fabricPackOutput);
        ((FabricItemAssetDefinitions) itemModels).fabric_setProcessedBlocks(blockStateGenerators.generators.keySet());
    }
}
