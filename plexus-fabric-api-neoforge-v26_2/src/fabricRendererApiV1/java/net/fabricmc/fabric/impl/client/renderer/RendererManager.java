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
// fabric-renderer-api-v1/src/client/java/net/fabricmc/fabric/impl/client/renderer/RendererManager.java (Fabric API's
// file, unchanged in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.client.renderer;

import net.fabricmc.fabric.api.client.renderer.v1.Renderer;

public final class RendererManager {
    private static Renderer activeRenderer;

    private RendererManager() {}

    public static Renderer getRenderer() {
        if (activeRenderer == null) {
            throw new UnsupportedOperationException(
                    "Attempted to retrieve active rendering plug-in before one was registered.");
        }

        return activeRenderer;
    }

    public static void registerRenderer(Renderer renderer) {
        if (renderer == null) {
            throw new NullPointerException("Attempted to register a null rendering plug-in. This is not supported.");
        }

        if (activeRenderer != null) {
            throw new UnsupportedOperationException(
                    "Attempted to register a second rendering plug-in. Multiple rendering plug-ins are not supported.");
        }

        activeRenderer = renderer;
    }
}
