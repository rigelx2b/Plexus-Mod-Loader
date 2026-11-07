package dev.plexusmodloader.api.event.player;

import dev.plexusmodloader.api.event.EventKey;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Player connection and respawn events, fired on the server thread of every server kind. */
@ApiStability(StabilityLevel.PREVIEW)
public final class PlayerEvents {

    /**
     * The game is building the new player object for a respawn (after death or when leaving the End)
     * and copying data from the old one. Fabric API: {@code ServerPlayerEvents.COPY_FROM}; NeoForge:
     * {@code PlayerEvent.Clone}.
     */
    public static final EventKey<PlayerCopyListener> COPY = EventKey.of("plexus:player/copy", PlayerCopyListener.class);

    /**
     * A player respawned and the new player object is in its world. Fabric API: {@code
     * ServerPlayerEvents.AFTER_RESPAWN}; NeoForge: {@code PlayerEvent.PlayerRespawnEvent}.
     */
    public static final EventKey<PlayerRespawnedListener> RESPAWNED =
            EventKey.of("plexus:player/respawned", PlayerRespawnedListener.class);

    /** A player finished joining the server and is in the world. */
    public static final EventKey<PlayerJoinedListener> JOINED =
            EventKey.of("plexus:player/joined", PlayerJoinedListener.class);

    /** A player is about to be removed from the server (disconnect or kick). */
    public static final EventKey<PlayerLeavingListener> LEAVING =
            EventKey.of("plexus:player/leaving", PlayerLeavingListener.class);

    /**
     * A player is logging in (after authentication and the game's ban, whitelist, and player-limit
     * checks); any listener may refuse them. Fired on the server thread.
     */
    public static final EventKey<LoginCheckListener> LOGIN_CHECK =
            EventKey.of("plexus:player/login_check", LoginCheckListener.class);

    /**
     * A server player starts a tick (20 times a second while the player is in a world). Fired on the
     * server thread, for connected and mock server players alike; not fired on the client. Fabric
     * API has no event for this (mods use a server tick event and loop over players); NeoForge:
     * {@code PlayerTickEvent.Pre}.
     */
    public static final EventKey<PlayerTickListener> TICK = EventKey.of("plexus:player/tick", PlayerTickListener.class);

    /**
     * A player clicked a bed and tries to lie down; a decision event: {@code DENY} keeps the player up. Fired on the
     * server thread before the game's own checks (daylight, distance, monsters), which still apply when no listener
     * denies. Fabric API: {@code EntitySleepEvents.ALLOW_SLEEPING}, which can also force sleep (not offered here);
     * NeoForge: {@code CanPlayerSleepEvent}.
     */
    public static final EventKey<AllowSleepingListener> ALLOW_SLEEPING =
            EventKey.of("plexus:player/allow_sleeping", AllowSleepingListener.class);

    /**
     * A player who wears working gliding gear presses jump in the air and the game is about to start gliding; a decision
     * event: {@code DENY} keeps the player from gliding. It can only forbid (see {@link AllowFallFlyingListener}).
     * Fabric API: {@code EntityElytraEvents.ALLOW}; NeoForge has no event for it (items decide with {@code
     * canElytraFly}).
     */
    public static final EventKey<AllowFallFlyingListener> ALLOW_FALL_FLYING =
            EventKey.of("plexus:player/allow_fall_flying", AllowFallFlyingListener.class);

    private PlayerEvents() {}
}
