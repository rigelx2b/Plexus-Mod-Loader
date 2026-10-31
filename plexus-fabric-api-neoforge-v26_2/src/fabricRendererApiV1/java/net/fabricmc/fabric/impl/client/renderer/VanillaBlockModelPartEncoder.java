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
// fabric-renderer-api-v1/src/client/java/net/fabricmc/fabric/impl/client/renderer/VanillaBlockModelPartEncoder.java
// (Fabric API's file as Forgified Fabric API changed it for NeoForge).
// Plexus Mod Loader (ADR-0031): reformatted; the conversion of NeoForge's ambient occlusion TriState is done here: the
// fabric-api-base next to Plexus Mod Loader is Fabric API's, which has no TriState.fromVanilla (Forgified Fabric API
// added it to its own).
package net.fabricmc.fabric.impl.client.renderer;

import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.client.renderer.v1.model.ModelHelper;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * Routines for adaptation of vanilla {@link BlockStateModelPart}s to FRAPI pipelines.
 */
public class VanillaBlockModelPartEncoder {
    public static void emitQuads(
            BlockStateModelPart part, QuadEmitter emitter, Predicate<@Nullable Direction> cullTest) {
        // This does not exactly match vanilla, but doing so requires hiding state all over the FRAPI impl.
        final TriState ao = fromVanilla(part.ambientOcclusion());

        for (int i = 0; i <= ModelHelper.NULL_FACE_ID; i++) {
            final Direction cullFace = ModelHelper.faceFromIndex(i);

            if (cullTest.test(cullFace)) {
                // Skip entire quad list if possible.
                continue;
            }

            final List<BakedQuad> quads = part.getQuads(cullFace);
            final int quadCount = quads.size();

            for (int j = 0; j < quadCount; j++) {
                final BakedQuad q = quads.get(j);
                final boolean neoAo = q.materialInfo().ambientOcclusion();
                emitter.cullFace(cullFace);
                emitter.fromBakedQuad(q);
                emitter.ambientOcclusion(neoAo ? ao : TriState.FALSE);
                emitter.shadeMode(ShadeMode.VANILLA);
                emitter.emit();
            }
        }
    }

    private static TriState fromVanilla(net.minecraft.util.TriState state) {
        return switch (state) {
            case TRUE -> TriState.TRUE;
            case FALSE -> TriState.FALSE;
            case DEFAULT -> TriState.DEFAULT;
        };
    }
}
