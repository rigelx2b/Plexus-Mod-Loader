package dev.plexusmodloader.game.layer.v1_20_1;

import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.game.version.layer.ChannelEndpoint;
import dev.plexusmodloader.game.version.layer.PayloadTransport;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;

/**
 * Carries Native API payloads as the game's custom payloads. In 1.20.1 a custom payload packet is a channel ID plus a
 * byte buffer, read and written by the packet itself, so a declared channel needs no decoding step: the receiving
 * hook takes the bytes of every declared channel, and the game ignores unknown channels on the other side.
 */
public final class Networking {

    private Networking() {}

    static ResourceLocation type(ResourceId channel) {
        return Views.id(channel);
    }

    /** {@code data} as the buffer a custom payload packet takes. */
    public static FriendlyByteBuf buffer(byte[] data) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
    }

    /** The bytes left in {@code buffer}, which stays as it is. */
    public static byte[] bytes(FriendlyByteBuf buffer) {
        byte[] data = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), data);
        return data;
    }

    /**
     * Head hook of the server's custom payload handler; {@code true} when the channel is declared. It runs on the
     * connection's thread, because 1.20.1's handler does not move to the server thread, so the mod runs on the server's
     * through {@code execute}. The packet's own buffer is not released here: {@code ServerboundCustomPayloadPacket.handle}
     * releases it after the handler, and a second release drops the connection.
     */
    static boolean receiveOnServer(ServerGamePacketListenerImpl listener, ServerboundCustomPayloadPacket packet) {
        ChannelEndpoint endpoint = LayerState.channels().get(Views.id(packet.getIdentifier()));
        if (endpoint == null) {
            return false;
        }
        byte[] data = bytes(packet.getData());
        ServerPlayer player = listener.player;
        player.serverLevel().getServer().execute(() -> endpoint.receiveOnServer(data, Views.player(player)));
        return true;
    }

    /** Sends payloads for the Native API. */
    static final class Transport implements PayloadTransport {

        private final GameSide side;

        Transport(GameSide side) {
            this.side = side;
        }

        @Override
        public void sendToPlayer(PlayerView player, ResourceId channel, byte[] data) {
            Player gamePlayer = Views.gamePlayer(player);
            if (!(gamePlayer instanceof ServerPlayer serverPlayer)) {
                throw new IllegalStateException("sendToPlayer needs a server-side player, got " + player);
            }
            serverPlayer.connection.send(new ClientboundCustomPayloadPacket(type(channel), buffer(data)));
        }

        @Override
        public void sendToServer(ResourceId channel, byte[] data) {
            if (side != GameSide.CLIENT) {
                throw new IllegalStateException("sendToServer is only available on the client");
            }
            dev.plexusmodloader.game.layer.v1_20_1.client.ClientHooks.sendToServer(
                    new ServerboundCustomPayloadPacket(type(channel), buffer(data)));
        }
    }
}
