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
// fabric-model-loading-api-v1/src/client/java/net/fabricmc/fabric/impl/client/model/loading/UnbakedModelDeserializerRegistry.java
// (Fabric API's file as Forgified Fabric API changed it for NeoForge).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.client.model.loading;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedModelDeserializer;
import net.minecraft.resources.Identifier;

public class UnbakedModelDeserializerRegistry {
    private static final Map<Identifier, UnbakedModelDeserializer> DESERIALIZERS = new ConcurrentHashMap<>();

    public static void register(Identifier id, UnbakedModelDeserializer deserializer) {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(id, "deserializer cannot be null");

        if (DESERIALIZERS.putIfAbsent(id, deserializer) != null) {
            throw new IllegalArgumentException(
                    "UnbakedModelDeserializer with identifier '" + id + "' already registered");
        }
    }

    public static UnbakedModelDeserializer get(Identifier id) {
        Objects.requireNonNull(id, "id cannot be null");

        return DESERIALIZERS.get(id);
    }
}
