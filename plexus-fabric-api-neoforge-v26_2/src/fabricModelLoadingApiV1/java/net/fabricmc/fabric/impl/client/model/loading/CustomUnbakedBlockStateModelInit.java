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
// fabric-model-loading-api-v1/src/client/java/net/fabricmc/fabric/impl/client/model/loading/CustomUnbakedBlockStateModelInit.java
// (Fabric API's file, unchanged in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.client.model.loading;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.CustomUnbakedBlockStateModel;
import net.minecraft.resources.Identifier;

/**
 * Register builtin custom unbaked block state models.
 */
public class CustomUnbakedBlockStateModelInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CustomUnbakedBlockStateModel.register(
                Identifier.fromNamespaceAndPath("fabric", "composite"), CompositeBlockStateModelImpl.Unbaked.CODEC);
    }
}
