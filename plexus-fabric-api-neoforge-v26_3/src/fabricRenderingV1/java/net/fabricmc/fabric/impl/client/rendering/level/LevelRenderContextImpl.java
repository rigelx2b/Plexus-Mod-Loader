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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/impl/client/rendering/level/LevelRenderContextImpl.java
// (Fabric API's file, unchanged in Forgified Fabric API).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.client.rendering.level;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.AbstractLevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.jspecify.annotations.Nullable;

public final class LevelRenderContextImpl
        implements AbstractLevelRenderContext, LevelTerrainRenderContext, LevelRenderContext {
    private GameRenderer gameRenderer;
    private LevelRenderer levelRenderer;
    private LevelRenderState levelRenderState;
    private SubmitNodeCollector nodeCollector;

    @Nullable
    private ChunkSectionsToRender sectionsToRender;

    @Nullable
    private PoseStack poseStack;

    public void prepare(
            GameRenderer gameRenderer,
            LevelRenderer levelRenderer,
            LevelRenderState levelRenderState,
            SubmitNodeCollector nodeCollector) {
        this.gameRenderer = gameRenderer;
        this.levelRenderer = levelRenderer;
        this.levelRenderState = levelRenderState;

        this.nodeCollector = nodeCollector;

        sectionsToRender = null;
        poseStack = null;
    }

    public void setSectionsToRender(ChunkSectionsToRender sectionsToRender) {
        this.sectionsToRender = sectionsToRender;
    }

    public void setPoseStack(@Nullable PoseStack poseStack) {
        this.poseStack = poseStack;
    }

    @Override
    public GameRenderer gameRenderer() {
        return gameRenderer;
    }

    @Override
    public LevelRenderer levelRenderer() {
        return levelRenderer;
    }

    @Override
    public LevelRenderState levelState() {
        return levelRenderState;
    }

    @Override
    public ChunkSectionsToRender sectionsToRender() {
        return sectionsToRender;
    }

    @Override
    public SubmitNodeCollector submitNodeCollector() {
        return nodeCollector;
    }

    @Override
    @Nullable
    public PoseStack poseStack() {
        return poseStack;
    }
}
