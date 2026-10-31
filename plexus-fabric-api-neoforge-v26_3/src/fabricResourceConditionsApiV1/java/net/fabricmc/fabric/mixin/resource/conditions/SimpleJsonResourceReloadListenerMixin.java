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
// fabric-resource-conditions-api-v1/src/main/java/net/fabricmc/fabric/mixin/resource/conditions/SimpleJsonResourceReloadListenerMixin.java
// (Fabric API's file as Forgified Fabric API has it).
// Plexus Mod Loader (ADR-0031): reformatted; wraps the parse in NeoForge 26.3's scanDirectory, which returns
// the map, and not in scanDirectoryWithModifier, which NeoForge 26.3 does not have (26.3 loads recipes as a
// registry). Fabric API 26.3 wraps it in prepare, where NeoForge 26.3 calls scanDirectory instead.
package net.fabricmc.fabric.mixin.resource.conditions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.impl.resource.conditions.ResourceConditionsImpl;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SimpleJsonResourceReloadListener.class)
public class SimpleJsonResourceReloadListenerMixin {
    @WrapOperation(
            method =
                    "scanDirectory(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/FileToIdConverter;Lcom/mojang/serialization/DynamicOps;Lcom/mojang/serialization/Codec;Ljava/util/Map;)Ljava/util/Map;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private static DataResult<Optional<?>> applyResourceConditions(
            Codec<?> instance,
            DynamicOps<JsonElement> dynamicOps,
            Object object,
            Operation<DataResult<Optional<?>>> original,
            @Local(argsOnly = true) FileToIdConverter resourceFinder,
            @Local(name = "entry") Map.Entry<Identifier, Resource> entry) {
        final JsonElement resourceData = (JsonElement) object;
        RegistryOps.@Nullable RegistryInfoLookup registryInfo = null;

        if (dynamicOps instanceof RegistryOpsAccessor registryOps) {
            registryInfo = registryOps.getRegistryInfoGetter();
        }

        if (resourceData.isJsonObject()) {
            JsonObject obj = resourceData.getAsJsonObject();

            final String dataType = resourceFinder.prefix();

            if (!ResourceConditionsImpl.applyResourceConditions(obj, dataType, entry.getKey(), registryInfo)) {
                return DataResult.success(Optional.empty());
            }
        }

        return original.call(instance, dynamicOps, object);
    }
}
