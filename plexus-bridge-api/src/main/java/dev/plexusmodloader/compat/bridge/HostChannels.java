package dev.plexusmodloader.compat.bridge;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;

/**
 * Whether the peer of a connection has a payload channel, as the host loader negotiated it (Forge on NeoForge, ADR-0051
 * decision 22). Forge's channels are registered with NeoForge as optional, so a peer without the Forge mod connects
 * without them; the game layer's hooks on Forge's sends ask this before sending, and drop a message for a peer that does
 * not have its channel, as a peer that does not know a payload would ignore it on MinecraftForge.
 *
 * <p>Without a host installed every channel counts as present. Types are {@link Object} because this module does not
 * see the game's classes: the connection (or its packet listener), its protocol and the channel's identifier.
 *
 * <p>Thread: {@link #install} on the launching thread before the game connects; {@link #has} from any thread.
 */
@ApiStability(StabilityLevel.INTERNAL)
public final class HostChannels {

    /** The host's answer. */
    @FunctionalInterface
    public interface Query {
        /**
         * Whether {@code connection}'s peer has channel {@code id} in {@code protocol}; true for a connection the host
         * never negotiated.
         */
        boolean has(Object connection, Object protocol, Object id);
    }

    private static volatile Query query;

    private HostChannels() {}

    /** Sets the host's answer, replacing an earlier one. */
    public static void install(Query hostQuery) {
        query = Objects.requireNonNull(hostQuery, "hostQuery");
    }

    /** Removes the host's answer; for tests. */
    public static void uninstall() {
        query = null;
    }

    /** Whether {@code connection}'s peer has channel {@code id} in {@code protocol}; true without a host. */
    public static boolean has(Object connection, Object protocol, Object id) {
        Query current = query;
        return current == null || current.has(connection, protocol, id);
    }
}
