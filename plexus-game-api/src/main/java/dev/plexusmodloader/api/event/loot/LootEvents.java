package dev.plexusmodloader.api.event.loot;

import dev.plexusmodloader.api.event.EventKey;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Events about loot tables: what blocks, mobs, chests, and fishing hand out. */
@ApiStability(StabilityLevel.PREVIEW)
public final class LootEvents {

    /**
     * A loot table has been rolled and its items may still be changed before the game hands them out. Fired on the
     * server thread for every roll of a table that has an ID (block drops, mob drops, chests, fishing, gifts), not for
     * tables built in code. Fabric API: {@code LootTableEvents.MODIFY_DROPS} (and {@code MODIFY} for the table
     * itself); NeoForge: global loot modifiers.
     *
     * <p>Unlike those, the listener edits the <em>rolled items</em> ({@link LootDrops}), not the table, so it sees what
     * the rolled conditions produced and can add, remove, or replace items. Listeners run in registration order.
     */
    public static final EventKey<LootModifyListener> MODIFY =
            EventKey.of("plexus:loot/modify", LootModifyListener.class);

    private LootEvents() {}
}
