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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/RenderStateDataKey.java (Fabric API's
// file, unchanged in Forgified Fabric API).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.client.rendering.v1;

import java.util.function.Supplier;

/**
 * A unique key representing extra data to attach to a render state.
 * @param <T> The type of the render state data.
 * @see FabricRenderState#getData(RenderStateDataKey)
 * @see FabricRenderState#setData(RenderStateDataKey, Object)
 */
public final class RenderStateDataKey<T> {
    private final Supplier<String> name;

    private RenderStateDataKey(Supplier<String> debugName) {
        this.name = debugName;
    }

    /**
     * Creates a new unique data key.
     * @param debugName The name of this data key, shown in error messages.
     * @param <T> The type of the render state data.
     * @return The newly created data key.
     */
    public static <T> RenderStateDataKey<T> create(Supplier<String> debugName) {
        return new RenderStateDataKey<>(debugName);
    }

    /**
     * Creates a new unique data key.
     * @param <T> The type of the render state data.
     * @return The newly created data key.
     */
    public static <T> RenderStateDataKey<T> create() {
        return new RenderStateDataKey<>(() -> "unnamed");
    }

    @Override
    public String toString() {
        return "RenderStateDataKey(" + name.get() + ")";
    }
}
