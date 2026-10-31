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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/SubmitRenderPhase.java (Fabric API's
// file, unchanged in Forgified Fabric API).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.client.rendering.v1;

import java.util.function.Function;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;

/**
 * A phase that submit nodes can be rendered with, for use with
 * {@link FabricOrderedSubmitNodeCollector#submitCustom(SubmitRenderPhase, SubmitNode)}.
 *
 * @param <T> The type of node that can be rendered in this phase.
 * @see SubmitNodeCollection
 * @see SubmitRenderPhases The built-in vanilla phases.
 * @see FabricOrderedSubmitNodeCollector#submitCustom(SubmitRenderPhase, SubmitNode)
 */
public class SubmitRenderPhase<T extends SubmitNode> {
    private final Function<SubmitNodeCollection, FeatureRenderPhase<? super T>> phaseGetter;

    public SubmitRenderPhase(Function<SubmitNodeCollection, FeatureRenderPhase<? super T>> phaseGetter) {
        this.phaseGetter = phaseGetter;
    }

    /**
     * Submit a node to the given collection using this phase.
     *
     * @param collection The collection to submit to.
     * @param node       The node to submit.
     */
    public void submit(SubmitNodeCollection collection, T node) {
        phaseGetter.apply(collection).submit(node);
    }
}
