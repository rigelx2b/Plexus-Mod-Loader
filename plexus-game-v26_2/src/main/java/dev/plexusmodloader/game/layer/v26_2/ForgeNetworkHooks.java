package dev.plexusmodloader.game.layer.v26_2;

import dev.plexusmodloader.compat.bridge.HostChannels;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge on NeoForge, F-N6 networking (ADR-0051 decision 22): what Forge's own network classes read from MinecraftForge's
 * patched game, which NeoForge's game does not have. The NeoForge host's transform rules ({@code ForgeNetworkRules} in
 * the Forge bridge) redirect those calls in Forge's {@code universal} jar here:
 *
 * <ul>
 *   <li>{@link #connectionProtocol}: Forge's {@code Channel.toVanillaPacket} and {@code Channel.validate} call {@code
 *       Connection.getProtocol()}, a member only MinecraftForge's patch adds.
 *   <li>{@link #currentServer}: Forge's {@code PacketDistributor.getServer()} calls Forge's {@code
 *       ServerLifecycleHooks.getCurrentServer()}, which only Forge's own server start sets.
 * </ul>
 *
 * <p>Thread: any; the server is published by the server thread and read by the threads that send packets.
 */
public final class ForgeNetworkHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");
    private static final Set<Identifier> DROPPED = ConcurrentHashMap.newKeySet();

    private static volatile MinecraftServer server;

    private ForgeNetworkHooks() {}

    /**
     * The protocol {@code connection} handles packets in: its packet listener's. MinecraftForge's {@code getProtocol()}
     * answers the outbound protocol's, which is the same once a phase has started.
     *
     * @throws IllegalStateException when the connection has no packet listener yet
     */
    public static ConnectionProtocol connectionProtocol(Connection connection) {
        PacketListener listener = connection.getPacketListener();
        if (listener == null) {
            throw new IllegalStateException(
                    "A connection without a packet listener has no protocol yet: " + connection);
        }
        return listener.protocol();
    }

    /** The server that is running, or {@code null} when none is, as Forge's {@code getCurrentServer()} answers. */
    public static MinecraftServer currentServer() {
        return server;
    }

    /** From the create-levels hook: {@code started} is the running server. */
    static void serverStarting(MinecraftServer started) {
        server = started;
    }

    /** From the stop hook: {@code stopped} no longer runs. */
    static void serverStopped(MinecraftServer stopped) {
        if (server == stopped) {
            server = null;
        }
    }

    /**
     * Forge's {@code PacketDistributor.PLAYER} and {@code Channel.send(MSG, Connection)} (and so {@code reply}):
     * {@code connection.send(packet)} when the peer has the packet's channel ({@link HostChannels}), else nothing. NeoForge's
     * client disconnects on a payload of a channel it does not have, and these sends skip NeoForge's own send check.
     */
    public static void sendIfChannel(Connection connection, Packet<?> packet) {
        if (hasChannel(connection, packet)) {
            connection.send(packet);
        }
    }

    /**
     * Forge's {@code PacketDistributor.ALL}: {@code players.broadcastAll(packet)} to each player whose connection has the
     * packet's channel, through {@code player.connection.send} as {@code broadcastAll} does. NeoForge's send check throws
     * for a player without it.
     */
    public static void broadcastIfChannel(PlayerList players, Packet<?> packet) {
        for (ServerPlayer player : players.getPlayers()) {
            if (hasChannel(player.connection, player.connection.protocol(), packet)) {
                player.connection.send(packet);
            }
        }
    }

    /** Whether {@code connection}'s peer has the channel of {@code packet}, a custom payload packet; logs a drop once. */
    public static boolean hasChannel(Connection connection, Packet<?> packet) {
        PacketListener listener = connection.getPacketListener();
        return listener == null || hasChannel(connection, listener.protocol(), packet);
    }

    /**
     * As {@link #hasChannel(Connection, Packet)} for {@code peer} in {@code protocol}: a connection, or the packet
     * listener of one ({@link HostChannels} gets its connection).
     */
    static boolean hasChannel(Object peer, ConnectionProtocol protocol, Packet<?> packet) {
        Identifier id;
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            id = custom.payload().type().id();
        } else if (packet instanceof ServerboundCustomPayloadPacket custom) {
            id = custom.payload().type().id();
        } else {
            return true;
        }
        if (HostChannels.has(peer, protocol, id)) {
            return true;
        }
        if (DROPPED.add(id)) {
            LOGGER.info(
                    "[Plexus Mod Loader] Forge on NeoForge: messages on Forge channel {} are not sent to a peer that does"
                            + " not have it (ADR-0051 22)",
                    id);
        }
        return false;
    }
}
