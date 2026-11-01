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
// Source: Fabric API (FabricMC), fabric-content-registries-v0 11.3.2+37b1aa249e, sources jar on maven.fabricmc.net,
// net/fabricmc/fabric/api/registry/CompostableRegistry.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.

package net.fabricmc.fabric.api.registry;

import net.fabricmc.fabric.api.util.Item2ObjectMap;
import net.fabricmc.fabric.impl.content.registry.CompostableRegistryImpl;

/**
 * Registry of items to 0.0-1.0 values, defining the chance of a given item
 * increasing the Composter block's level.
 */
public interface CompostableRegistry extends Item2ObjectMap<Float> {
    CompostableRegistry INSTANCE = new CompostableRegistryImpl();
}
