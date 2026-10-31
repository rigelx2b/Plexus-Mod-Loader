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
// fabric-renderer-api-v1/src/client/java/net/fabricmc/fabric/api/client/renderer/v1/sprite/FabricTextureAtlas.java
// (Fabric API's file, unchanged in Forgified Fabric API).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.client.renderer.v1.sprite;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/**
 * Note: This interface is automatically implemented on {@link TextureAtlas} via Mixin and interface injection.
 */
public interface FabricTextureAtlas {
    /**
     * Retrieves the sprite finder for this atlas. The returned instance is only valid until the next call to
     * {@link TextureAtlas#upload(SpriteLoader.Preparations)}, and thus should not be persisted across resource
     * reloads.
     *
     * <p><b>This method should not be used during a resource reload</b> as this atlas will only be populated with new
     * sprites towards the end of the resource reload. In this case, use
     * {@link FabricMaterialBaker#spriteFinder(Identifier)} or {@link FabricPreparations#spriteFinder()}
     * instead.
     *
     * @return the sprite finder for this atlas
     */
    default SpriteFinder spriteFinder() {
        throw new UnsupportedOperationException();
    }
}
