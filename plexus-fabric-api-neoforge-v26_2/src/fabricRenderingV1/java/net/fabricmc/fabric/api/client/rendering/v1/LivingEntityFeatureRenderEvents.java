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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/LivingEntityFeatureRenderEvents.java
// (Fabric API's file, unchanged in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.client.rendering.v1;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * Events related to living entity {@link RenderLayer}s.
 * To register a renderer, see {@link LivingEntityRenderLayerRegistrationCallback} instead.
 */
public final class LivingEntityFeatureRenderEvents {
    /**
     * An event that can prevent capes from rendering.
     */
    public static final Event<AllowCapeRender> ALLOW_CAPE_RENDER =
            EventFactory.createArrayBacked(AllowCapeRender.class, listeners -> state -> {
                for (AllowCapeRender listener : listeners) {
                    if (!listener.allowCapeRender(state)) {
                        return false;
                    }
                }

                return true;
            });

    @FunctionalInterface
    public interface AllowCapeRender {
        /**
         * @return false to prevent rendering the cape
         */
        boolean allowCapeRender(AvatarRenderState state);
    }

    private LivingEntityFeatureRenderEvents() {}
}
