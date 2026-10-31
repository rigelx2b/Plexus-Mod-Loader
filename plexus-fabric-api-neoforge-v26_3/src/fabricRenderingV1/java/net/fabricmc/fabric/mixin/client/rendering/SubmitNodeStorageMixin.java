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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/mixin/client/rendering/SubmitNodeStorageMixin.java (Fabric
// API's file, unchanged in Forgified Fabric API).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; the mixin no longer declares that it implements SubmitNodeCollector, which
// SubmitNodeStorage implements already, and shadows order(int) instead: Plexus Mod Loader's host check counts that
// interface as added and takes the defaults NeoForge's SubmitNodeStorageExtension (a SubmitNodeCollector itself)
// overrides for a conflict. The method and what it does are the same.
package net.fabricmc.fabric.mixin.client.rendering;

import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SubmitNodeStorage.class)
abstract class SubmitNodeStorageMixin {
    @Shadow
    public abstract SubmitNodeCollection order(int order);

    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
        order(0).submitCustom(phase, node);
    }
}
