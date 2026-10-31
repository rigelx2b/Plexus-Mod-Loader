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
// fabric-entity-events-v1/src/main/java/net/fabricmc/fabric/mixin/entity/event/EntityMixin.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.entity.event;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
abstract class EntityMixin {
    @Shadow
    private Level level;

    @WrapOperation(
            method = "teleport",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/Entity;teleportCrossDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;"))
    private Entity afterDimensionChanged(
            Entity instance,
            ServerLevel sourceLevel,
            ServerLevel targetWorld,
            TeleportTransition teleportTransition,
            Operation<Entity> original) {
        // Ret will only have an entity if the teleport worked (entity not removed, teleportTarget was valid, entity was
        // successfully created)
        Entity ret = original.call(instance, sourceLevel, targetWorld, teleportTransition);

        if (ret != null) {
            ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL
                    .invoker()
                    .afterChangeLevel((Entity) (Object) this, ret, (ServerLevel) this.level, (ServerLevel) ret.level());
        }

        return ret;
    }
}
