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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/impl/client/rendering/ArmorRendererRegistryImpl.java (Fabric
// API's file as Forgified Fabric API changed it for NeoForge).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.client.rendering;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer.Factory;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import org.jspecify.annotations.Nullable;

public class ArmorRendererRegistryImpl {
    private static final Map<Item, Factory> FACTORIES = new ConcurrentHashMap<>();
    private static final Map<Item, ArmorRenderer> RENDERERS = new ConcurrentHashMap<>();

    public static void register(ArmorRenderer.Factory factory, ItemLike... items) {
        Objects.requireNonNull(factory, "renderer factory is null");

        if (items.length == 0) {
            throw new IllegalArgumentException("Armor renderer registered for no item");
        }

        for (ItemLike item : items) {
            Objects.requireNonNull(item.asItem(), "armor item is null");

            if (FACTORIES.putIfAbsent(item.asItem(), factory) != null) {
                throw new IllegalArgumentException(
                        "Custom armor renderer already exists for " + BuiltInRegistries.ITEM.getKey(item.asItem()));
            }
        }
    }

    public static void register(ArmorRenderer renderer, ItemLike... items) {
        Objects.requireNonNull(renderer, "renderer is null");
        register(context -> renderer, items);
    }

    @Nullable
    public static ArmorRenderer get(Item item) {
        return RENDERERS.get(item);
    }

    public static void createArmorRenderers(EntityRendererProvider.Context context) {
        RENDERERS.clear();
        FACTORIES.forEach((item, factory) -> RENDERERS.put(item, factory.createArmorRenderer(context)));
    }
}
