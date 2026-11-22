package dev.plexusmodloader.game.layer.v26_2.client;

import dev.plexusmodloader.game.layer.v26_2.ForgeNetworkHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;

/**
 * Forge on NeoForge, F-N6 networking (ADR-0051 decision 22): the client's side of {@link ForgeNetworkHooks}. Forge's
 * {@code PacketDistributor.SERVER} sends with {@code ClientPacketListener.send}, whose NeoForge send check refuses a
 * channel the server does not have; the host's rule redirects that call here.
 *
 * <p>Thread: the thread that sends.
 */
public final class ForgeNetworkClientHooks {

    private ForgeNetworkClientHooks() {}

    /** {@code listener.send(packet)} when the server has the packet's channel, else nothing. */
    public static void sendIfChannel(ClientPacketListener listener, Packet<?> packet) {
        if (ForgeNetworkHooks.hasChannel(listener.getConnection(), packet)) {
            listener.send(packet);
        }
    }
}
