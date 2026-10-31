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
// fabric-renderer-api-v1/src/client/java/net/fabricmc/fabric/mixin/client/renderer/sprite/MaterialBakerMixin.java
// (Fabric API's file, unchanged in Forgified Fabric API).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.client.renderer.sprite;

import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricMaterialBaker;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.fabricmc.fabric.impl.client.renderer.MissingSpriteFinderImpl;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MaterialBaker.class)
abstract class MaterialBakerMixin implements FabricMaterialBaker {
    @Shadow
    @Final
    private SpriteLoader.Preparations blockAtlas;

    @Shadow
    @Final
    private SpriteLoader.Preparations itemAtlas;

    @Unique
    @Nullable
    private volatile MissingSpriteFinderImpl missingSpriteFinder;

    @Override
    public SpriteFinder spriteFinder(Identifier atlasId) {
        if (atlasId.equals(AtlasIds.BLOCKS)) {
            return blockAtlas.spriteFinder();
        } else if (atlasId.equals(AtlasIds.ITEMS)) {
            return itemAtlas.spriteFinder();
        }

        MissingSpriteFinderImpl result = missingSpriteFinder;

        if (result == null) {
            synchronized (this) {
                result = missingSpriteFinder;

                if (result == null) {
                    missingSpriteFinder = result = new MissingSpriteFinderImpl(blockAtlas.missing());
                }
            }
        }

        return result;
    }
}
