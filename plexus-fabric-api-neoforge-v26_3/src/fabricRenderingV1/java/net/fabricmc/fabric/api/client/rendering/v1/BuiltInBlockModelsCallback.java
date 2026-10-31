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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/BuiltInBlockModelsCallback.java
// (Fabric API's file, unchanged in Forgified Fabric API).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.client.rendering.v1;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BuiltInBlockModels;
import net.minecraft.client.renderer.block.model.BlockModel;

/**
 * Called when custom {@link BlockModel.Unbaked BlockModels} are registered in
 * {@link BuiltInBlockModels#createBlockModels(BlockColors)}.
 *
 * <p>This allows for overriding block models which eventually end up being used in
 * {@link net.minecraft.client.renderer.block.BlockModelResolver}.
 */
public interface BuiltInBlockModelsCallback {
    Event<BuiltInBlockModelsCallback> EVENT =
            EventFactory.createArrayBacked(BuiltInBlockModelsCallback.class, listeners -> builder -> {
                for (BuiltInBlockModelsCallback listener : listeners) {
                    listener.createBlockModels(builder);
                }
            });

    /**
     * @param builder the block models builder instance
     */
    void createBlockModels(BuiltInBlockModels.Builder builder);
}
