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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/SpriteSourceRegistry.java (Fabric
// API's file, unchanged in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.client.rendering.v1;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.impl.client.rendering.SpriteSourceRegistryImpl;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.resources.Identifier;

/**
 * A registry for custom {@link SpriteSource}s. Registered types will be automatically available for use in atlas definition JSON files.
 */
public final class SpriteSourceRegistry {
    private SpriteSourceRegistry() {}

    /**
     * Registers a new {@link SpriteSource} by providing a codec for it.
     *
     * @param id the identifier of the sprite source type
     * @param codec the codec for the sprite source type
     */
    public static void register(Identifier id, MapCodec<? extends SpriteSource> codec) {
        SpriteSourceRegistryImpl.register(id, codec);
    }
}
