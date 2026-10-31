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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/mixin/client/rendering/SubmitNodeCollectionMixin.java (Fabric
// API's file, unchanged in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.client.rendering;

import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SubmitNodeCollection.class)
abstract class SubmitNodeCollectionMixin implements OrderedSubmitNodeCollector {
    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
        phase.submit((SubmitNodeCollection) (Object) this, node);
    }
}
