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
// fabric-entity-events-v1/src/main/java/net/fabricmc/fabric/api/entity/event/v1/ServerEntityCombatEvents.java.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.entity.event.v1;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Events related to entities in combat.
 */
public final class ServerEntityCombatEvents {
    /**
     * An event that is called after an entity is directly responsible for killing another entity.
     *
     * @see Entity#killedEntity(ServerLevel, LivingEntity, DamageSource)
     */
    public static final Event<AfterKilledOtherEntity> AFTER_KILLED_OTHER_ENTITY = EventFactory.createArrayBacked(
            AfterKilledOtherEntity.class, callbacks -> (level, entity, killedEntity, damageSource) -> {
                for (AfterKilledOtherEntity callback : callbacks) {
                    callback.afterKilledOtherEntity(level, entity, killedEntity, damageSource);
                }
            });

    @FunctionalInterface
    public interface AfterKilledOtherEntity {
        /**
         * Called after an entity has killed another entity.
         *
         * @param level the level
         * @param entity the entity
         * @param killedEntity the entity which was killed by the {@code entity}
         * @param damageSource the damage source that killed the entity
         */
        void afterKilledOtherEntity(
                ServerLevel level, Entity entity, LivingEntity killedEntity, DamageSource damageSource);
    }

    private ServerEntityCombatEvents() {}
}
