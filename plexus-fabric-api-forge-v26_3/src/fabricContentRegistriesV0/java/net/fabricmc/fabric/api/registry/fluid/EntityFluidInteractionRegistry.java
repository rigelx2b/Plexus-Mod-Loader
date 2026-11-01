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
// Source: Fabric API (FabricMC), fabric-content-registries-v0 15.0.6+74ed1ea55d, sources jar on maven.fabricmc.net,
// net/fabricmc/fabric/api/registry/fluid/EntityFluidInteractionRegistry.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.

package net.fabricmc.fabric.api.registry.fluid;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import net.fabricmc.fabric.impl.content.registry.fluid.EntityFluidInteractionRegistryImpl;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

/**
 * A registry for fluid tags, that should be tracked by {@link Entity}'s {@link EntityFluidInteraction}.
 */
public final class EntityFluidInteractionRegistry {
    private EntityFluidInteractionRegistry() {}

    /**
     * Registers a tracked fluid tag.
     *
     * @param fluid tag representing a fluid type that should be tracked.
     * @param behavior an instance defining the behavior of the fluid
     */
    public static void register(TagKey<Fluid> fluid, FluidBehavior behavior) {
        Objects.requireNonNull(fluid, "fluid can't be null!");
        Objects.requireNonNull(behavior, "behavior can't be null!");

        EntityFluidInteractionRegistryImpl.register(fluid, behavior);
    }

    /**
     * Returns the custom registered fluid behavior.
     *
     * @param fluid  tag representing a fluid type
     * @return connected fluid behavior instance or null if not set
     */
    @Nullable
    public static FluidBehavior getFluidBehavior(TagKey<Fluid> fluid) {
        Objects.requireNonNull(fluid, "fluid can't be null!");

        return EntityFluidInteractionRegistryImpl.getFluidBehavior(fluid);
    }

    /**
     * Returns a collection of registered fluid tags with custom behavior.
     *
     * @return a collection of fluid tags
     */
    public static Collection<TagKey<Fluid>> getCustomInteractableFluids() {
        return Collections.unmodifiableCollection(EntityFluidInteractionRegistryImpl.getTrackedFluids());
    }
}
