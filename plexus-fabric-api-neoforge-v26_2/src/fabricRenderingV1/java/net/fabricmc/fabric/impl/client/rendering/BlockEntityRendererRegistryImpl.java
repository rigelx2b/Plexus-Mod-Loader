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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/impl/client/rendering/BlockEntityRendererRegistryImpl.java
// (Fabric API's file as Forgified Fabric API changed it for NeoForge).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.client.rendering;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class BlockEntityRendererRegistryImpl {
    private static final Map<BlockEntityType<?>, BlockEntityRendererProvider<?, ?>> MAP = new ConcurrentHashMap<>();
    private static BiConsumer<BlockEntityType<?>, BlockEntityRendererProvider<?, ?>> handler =
            (type, function) -> MAP.put(type, function);

    public static <E extends BlockEntity, S extends BlockEntityRenderState> void register(
            BlockEntityType<E> blockEntityType,
            BlockEntityRendererProvider<? super E, ? super S> blockEntityRendererProvider) {
        handler.accept(blockEntityType, blockEntityRendererProvider);
    }

    public static void setup(BiConsumer<BlockEntityType<?>, BlockEntityRendererProvider<?, ?>> vanillaHandler) {
        MAP.forEach(vanillaHandler);
        handler = vanillaHandler;
    }

    private BlockEntityRendererRegistryImpl() {}
}
