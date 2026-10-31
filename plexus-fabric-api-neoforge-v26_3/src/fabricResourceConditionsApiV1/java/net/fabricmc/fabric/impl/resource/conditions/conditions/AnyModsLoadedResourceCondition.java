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
// fabric-resource-conditions-api-v1/src/main/java/net/fabricmc/fabric/impl/resource/conditions/conditions/AnyModsLoadedResourceCondition.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.resource.conditions.conditions;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditionType;
import net.fabricmc.fabric.impl.resource.conditions.DefaultResourceConditionTypes;
import net.fabricmc.fabric.impl.resource.conditions.ResourceConditionsImpl;
import net.minecraft.resources.RegistryOps;
import org.jspecify.annotations.Nullable;

public record AnyModsLoadedResourceCondition(List<String> modIds) implements ResourceCondition {
    public static final MapCodec<AnyModsLoadedResourceCondition> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(Codec.STRING.listOf().fieldOf("values").forGetter(AnyModsLoadedResourceCondition::modIds))
                    .apply(instance, AnyModsLoadedResourceCondition::new));

    @Override
    public ResourceConditionType<?> getType() {
        return DefaultResourceConditionTypes.ANY_MODS_LOADED;
    }

    @Override
    public boolean test(RegistryOps.@Nullable RegistryInfoLookup registryInfo) {
        return ResourceConditionsImpl.modsLoaded(this.modIds(), false);
    }
}
