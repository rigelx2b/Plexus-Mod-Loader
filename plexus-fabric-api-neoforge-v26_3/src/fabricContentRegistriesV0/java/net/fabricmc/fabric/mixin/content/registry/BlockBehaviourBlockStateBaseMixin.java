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
// fabric-content-registries-v0/src/main/java/net/fabricmc/fabric/mixin/content/registry/BlockBehaviourBlockStateBaseMixin.java (Fabric API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.content.registry;

import net.fabricmc.fabric.impl.content.registry.OxidizableBlocksRegistryImpl;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockBehaviourBlockStateBaseMixin extends StateHolder<Block, BlockState>
        implements OxidizableBlocksRegistryImpl.RandomTickCacheRefresher {
    @Shadow
    protected abstract BlockState asState();

    @Shadow
    private boolean isRandomlyTicking;

    protected BlockBehaviourBlockStateBaseMixin(
            Block owner, Property<?>[] propertyKeys, Comparable<?>[] propertyValues) {
        super(owner, propertyKeys, propertyValues);
    }

    @Override
    public void fabric_api$refreshRandomTickCache() {
        this.isRandomlyTicking = ((BlockBehaviourAccessor) this.owner).callIsRandomlyTicking(this.asState());
    }
}
