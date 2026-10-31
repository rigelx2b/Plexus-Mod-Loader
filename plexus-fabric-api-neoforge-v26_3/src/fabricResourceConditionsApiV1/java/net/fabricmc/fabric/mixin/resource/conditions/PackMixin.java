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
// Source: Fabric API (FabricMC/fabric), tag 0.165.0+26.3,
// fabric-resource-conditions-api-v1/src/main/java/net/fabricmc/fabric/mixin/resource/conditions/PackMixin.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.resource.conditions;

import com.llamalad7.mixinextras.sugar.Local;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.resource.conditions.v1.FabricOverlayMetadataSection;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Pack.class)
public class PackMixin {
    @ModifyVariable(method = "readPackMetadata", at = @At("STORE"), name = "overlaySet")
    private static List<String> applyOverlayConditions(
            List<String> overlays, @Local(name = "pack") PackMetadataResources pack) throws IOException {
        List<String> appliedOverlays = new ArrayList<>(overlays);
        FabricOverlayMetadataSection overlayMetadata = pack.getMetadataSection(FabricOverlayMetadataSection.TYPE);

        if (overlayMetadata != null) {
            for (FabricOverlayMetadataSection.Entry entry : overlayMetadata.overlays()) {
                if (entry.condition().test(null)) {
                    appliedOverlays.add(entry.overlay());
                }
            }
        }

        return List.copyOf(appliedOverlays);
    }
}
