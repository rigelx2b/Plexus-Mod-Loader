package dev.plexusmodloader.api.event.entity;

import dev.plexusmodloader.api.entity.LivingEntityView;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import dev.plexusmodloader.api.world.BlockPos;

/** Listener for {@link EntityEvents#STARTED_SLEEPING} and {@link EntityEvents#STOPPING_SLEEP}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface SleepListener {

    /**
     * Called on the server thread. For {@code STARTED_SLEEPING} the entity has just lain down and is asleep; for {@code
     * STOPPING_SLEEP} it is still asleep and the game is about to wake it up (the player left the bed, the bed was
     * destroyed, the night ended, or damage woke it). The view is valid only during the call.
     *
     * @param entity the sleeping living entity: a player, villager, or any other that sleeps
     * @param bed the position of the bed or other block it lies on
     */
    void onSleep(LivingEntityView entity, BlockPos bed);
}
