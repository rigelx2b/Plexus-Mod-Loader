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
// fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/mixin/datagen/TagsProviderMixin.java (Fabric API's
// file as Forgified Fabric API has it).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.datagen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.fabricmc.fabric.impl.datagen.TagAliasGenerator;
import net.minecraft.core.Registry;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TagsProvider.class)
public class TagsProviderMixin<T> {
    @Shadow
    @Final
    protected ResourceKey<? extends Registry<T>> registryKey;

    @Unique
    private PackOutput.PathProvider tagAliasPathResolver;

    @Inject(
            method =
                    "<init>(Lnet/minecraft/data/PackOutput;Lnet/minecraft/resources/ResourceKey;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;Ljava/lang/String;)V",
            at = @At("RETURN"))
    private void initPathResolver(
            PackOutput output,
            ResourceKey<? extends Registry<T>> registryRef,
            CompletableFuture<?> registriesFuture,
            CompletableFuture<?> parentTagLookupFuture,
            String modId,
            CallbackInfo info) {
        tagAliasPathResolver =
                output.createPathProvider(PackOutput.Target.DATA_PACK, TagAliasGenerator.getDirectory(registryRef));
    }

    @SuppressWarnings("unchecked")
    @WrapOperation(
            method = "lambda$run$2",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ljava/util/concurrent/CompletableFuture;allOf([Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> addTagAliasGroupBuilders(
            CompletableFuture<?>[] cfs,
            Operation<CompletableFuture<Void>> original,
            @Local(argsOnly = true) CachedOutput cache) {
        // exclude providers that don't use fabric API
        if (!((Object) this instanceof FabricTagsProvider)) {
            return original.call((Object) cfs);
        }

        // Note: no pattern matching instanceof so that we can cast directly to FabricTagsProvider<T> instead of a
        // wildcard
        Map<Identifier, FabricTagsProvider<T>.AliasGroupBuilder> builders =
                ((FabricTagsProvider<T>) (Object) this).getAliasGroupBuilders();
        CompletableFuture<?>[] newFutures = Arrays.copyOf(cfs, cfs.length + builders.size());
        int index = cfs.length;

        for (Map.Entry<Identifier, FabricTagsProvider<T>.AliasGroupBuilder> entry : builders.entrySet()) {
            newFutures[index++] = TagAliasGenerator.writeTagAlias(
                    cache,
                    tagAliasPathResolver,
                    registryKey,
                    entry.getKey(),
                    entry.getValue().getTags());
        }

        return original.call((Object) newFutures);
    }
}
