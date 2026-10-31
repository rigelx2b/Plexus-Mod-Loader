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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/impl/client/rendering/RegistrationHelperImpl.java (Fabric
// API's file, unchanged in Forgified Fabric API).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.client.rendering;

import java.util.Objects;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

public final class RegistrationHelperImpl implements LivingEntityRenderLayerRegistrationCallback.RegistrationHelper {
    private final Function<RenderLayer<?, ?>, Boolean> delegate;

    public RegistrationHelperImpl(Function<RenderLayer<?, ?>, Boolean> delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T extends EntityRenderState> void register(RenderLayer<T, ? extends EntityModel<T>> renderLayer) {
        Objects.requireNonNull(renderLayer, "Render layer cannot be null");
        this.delegate.apply(renderLayer);
    }
}
