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
// fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/mixin/datagen/advancement/VanillaAdventureAdvancementsMixin.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.datagen.advancement;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.data.advancements.packs.VanillaAdventureAdvancements;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VanillaAdventureAdvancements.class)
public class VanillaAdventureAdvancementsMixin {
    @ModifyExpressionValue(
            method = "validateMobsToKill",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/data/worldgen/BootstrapContext;listContextElements(Lnet/minecraft/resources/ResourceKey;)Ljava/util/stream/Stream;"))
    private Stream<Holder.Reference<EntityType<?>>> onlyCheckVanillaEntities(
            Stream<Holder.Reference<EntityType<?>>> entities) {
        return entities.filter(
                entity -> entity.key().identifier().getNamespace().equals(Identifier.DEFAULT_NAMESPACE));
    }
}
