package dev.plexusmodloader.game.layer.v26_3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.compat.bridge.HostChannels;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0051 decision 22, second amendment: the hooks on Forge's sends send a Forge channel's message only to a peer that
 * has the channel, as the host's negotiated channels ({@link HostChannels}) answer.
 */
class ForgeNetworkHooksTest {

    private record Payload(Identifier id) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return new Type<>(id);
        }
    }

    private static final Identifier PRESENT = Identifier.fromNamespaceAndPath("forge_mod", "present");
    private static final Identifier ABSENT = Identifier.fromNamespaceAndPath("forge_mod", "absent");

    @AfterEach
    void uninstall() {
        HostChannels.uninstall();
    }

    @Test
    void aPayloadGoesOnlyToAPeerWithItsChannel() {
        HostChannels.install((connection, protocol, id) -> protocol == ConnectionProtocol.PLAY && id.equals(PRESENT));
        Packet<?> present = new ClientboundCustomPayloadPacket(new Payload(PRESENT));
        Packet<?> absent = new ClientboundCustomPayloadPacket(new Payload(ABSENT));

        assertTrue(ForgeNetworkHooks.hasChannel(new Object(), ConnectionProtocol.PLAY, present));
        assertFalse(ForgeNetworkHooks.hasChannel(new Object(), ConnectionProtocol.PLAY, absent));
        // Not a custom payload: never held back.
        assertTrue(
                ForgeNetworkHooks.hasChannel(new Object(), ConnectionProtocol.PLAY, new ClientboundKeepAlivePacket(1)));
    }

    @Test
    void withoutAHostEveryChannelCounts() {
        assertTrue(ForgeNetworkHooks.hasChannel(
                new Object(), ConnectionProtocol.PLAY, new ClientboundCustomPayloadPacket(new Payload(ABSENT))));
    }

    @Test
    void aConnectionWithoutAListenerSendsAsBefore() {
        HostChannels.install((connection, protocol, id) -> false);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        Packet<?> packet = new ClientboundCustomPayloadPacket(new Payload(ABSENT));

        ForgeNetworkHooks.sendIfChannel(connection, packet);

        assertSame(packet, channel.readOutbound());
        assertNull(channel.readOutbound());
    }
}
