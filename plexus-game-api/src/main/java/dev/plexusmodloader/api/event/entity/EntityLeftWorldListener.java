package dev.plexusmodloader.api.event.entity;

import dev.plexusmodloader.api.entity.EntityView;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import dev.plexusmodloader.api.world.WorldView;

/** Listener for {@link EntityEvents#LEFT_WORLD}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface EntityLeftWorldListener {

    /**
     * Called on the server thread just before the world stops tracking the entity, while the entity is
     * still readable: it was removed (killed, discarded, picked up), unloaded with its chunk, or is
     * leaving for another dimension. Do not move or revive the entity from the listener. Keep it
     * cheap: chunks full of entities unload often.
     *
     * @param entity the entity, of any kind; valid only during the call
     * @param world the world it leaves
     */
    void onEntityLeftWorld(EntityView entity, WorldView world);
}
