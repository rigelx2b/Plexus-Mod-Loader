package dev.plexusmodloader.api.event.loot;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link LootEvents#MODIFY}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface LootModifyListener {

    /**
     * Called on the server thread after a loot table was rolled, before the items are handed out.
     *
     * @param table the loot table's ID, for example {@code minecraft:blocks/stone} or {@code minecraft:entities/pig}
     * @param drops the rolled items; valid only during the call. Changes apply to this roll only
     */
    void onModify(ResourceId table, LootDrops drops);
}
