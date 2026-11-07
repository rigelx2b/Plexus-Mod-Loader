package dev.plexusmodloader.api.event.block;

import dev.plexusmodloader.api.event.EventKey;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Block events, fired on the server thread. */
@ApiStability(StabilityLevel.PREVIEW)
public final class BlockEvents {

    /**
     * A player is about to break a block (survival or creative); a decision event (see {@link
     * dev.plexusmodloader.api.event.EventResult}): {@code DENY} keeps the block. Fired before the game's
     * own checks for the held item, game master blocks, and adventure mode, so it also fires for
     * breaks the game then refuses. Fabric API: {@code PlayerBlockBreakEvents.BEFORE}; NeoForge:
     * {@code BlockEvent.BreakEvent}.
     */
    public static final EventKey<BlockBreakListener> BREAK =
            EventKey.of("plexus:block/break", BlockBreakListener.class);

    /**
     * A {@link #BREAK} listener refused a player's break and the block stays. Fired once per refused
     * break, after the refusal; breaks the game itself refuses (wrong held item, adventure mode) do not
     * fire it. Fabric API: {@code PlayerBlockBreakEvents.CANCELED}.
     */
    public static final EventKey<BlockBreakCancelledListener> BREAK_CANCELLED =
            EventKey.of("plexus:block/break_cancelled", BlockBreakCancelledListener.class);

    /** A player broke a block (survival or creative). */
    public static final EventKey<BlockBrokenListener> BROKEN =
            EventKey.of("plexus:block/broken", BlockBrokenListener.class);

    /**
     * A player is about to place a block from a block item (including beds and doors); a decision
     * event: {@code DENY} refuses it. NeoForge: {@code BlockEvent.EntityPlaceEvent} for players.
     */
    public static final EventKey<BlockPlaceListener> PLACE =
            EventKey.of("plexus:block/place", BlockPlaceListener.class);

    /** A player placed a block from a block item. */
    public static final EventKey<BlockPlacedListener> PLACED =
            EventKey.of("plexus:block/placed", BlockPlacedListener.class);

    private BlockEvents() {}
}
