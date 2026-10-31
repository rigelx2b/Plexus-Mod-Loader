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
// fabric-resource-conditions-api-v1/src/main/java/net/fabricmc/fabric/impl/resource/conditions/conditions/RegistryContainsResourceCondition.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.resource.conditions.conditions;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditionType;
import net.fabricmc.fabric.impl.resource.conditions.DefaultResourceConditionTypes;
import net.fabricmc.fabric.impl.resource.conditions.ResourceConditionsImpl;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;

public record RegistryContainsResourceCondition(Identifier registry, List<Identifier> entries)
        implements ResourceCondition {
    // Cannot use registry-bound codec because they fail parsing if nonexistent,
    // and resource conditions themselves should not fail to parse on condition failure
    public static final MapCodec<RegistryContainsResourceCondition> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                            Identifier.CODEC
                                    .fieldOf("registry")
                                    .orElse(Registries.ITEM.identifier())
                                    .forGetter(RegistryContainsResourceCondition::registry),
                            Identifier.CODEC
                                    .listOf()
                                    .fieldOf("values")
                                    .forGetter(RegistryContainsResourceCondition::entries))
                    .apply(instance, RegistryContainsResourceCondition::new));

    public RegistryContainsResourceCondition(Identifier registry, Identifier... entries) {
        this(registry, List.of(entries));
    }

    @SafeVarargs
    public <T> RegistryContainsResourceCondition(ResourceKey<T>... entries) {
        this(
                entries[0].registry(),
                Arrays.stream(entries).map(ResourceKey::identifier).toList());
    }

    @Override
    public ResourceConditionType<?> getType() {
        return DefaultResourceConditionTypes.REGISTRY_CONTAINS;
    }

    @Override
    public boolean test(RegistryOps.@Nullable RegistryInfoLookup registryInfo) {
        return ResourceConditionsImpl.registryContains(registryInfo, this.registry(), this.entries());
    }
}
