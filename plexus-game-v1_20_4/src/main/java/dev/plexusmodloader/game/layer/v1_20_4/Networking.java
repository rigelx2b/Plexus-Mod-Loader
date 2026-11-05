package dev.plexusmodloader.game.layer.v1_20_4;

import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.game.version.layer.ChannelEndpoint;
import dev.plexusmodloader.game.version.layer.PayloadTransport;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;

/**
 * Carries Native API payloads as the game's custom payloads. In 1.20.4 a custom payload is a channel ID plus the
 * bytes that follow it, and the packets decode it in {@code readPayload}, which knows only the game's own payloads
 * and discards the rest. {@link #readPayload} runs first and keeps the bytes of every declared channel, so they
 * travel as {@code <channel id> + payload} like any custom payload; unknown channels on the other side are
 * discarded by the game.
 */
public final class Networking {

    private Networking() {}

    /** A Native API payload: the channel and the bytes the mod's codec produced. */
    public record PlexusPayload(ResourceLocation id, byte[] data) implements CustomPacketPayload {

        @Override
        public void write(FriendlyByteBuf buffer) {
            buffer.writeBytes(data);
        }
    }

    static ResourceLocation type(ResourceId channel) {
        return Views.id(channel);
    }

    /**
     * Head hook of {@code readPayload(ResourceLocation, FriendlyByteBuf)} of the custom payload packets: the payload
     * of a declared channel or configuration exchange, with all remaining bytes; null for the game's own payloads
     * and unknown channels (the game goes on).
     */
    static CustomPacketPayload readPayload(ResourceLocation id, FriendlyByteBuf buffer) {
        ResourceId channel = Views.id(id);
        if (!LayerState.channels().containsKey(channel)
                && !LayerState.configurationExchanges().containsKey(channel)) {
            return null;
        }
        byte[] data = new byte[buffer.readableBytes()];
        buffer.readBytes(data);
        return new PlexusPayload(id, data);
    }

    /** Head hook of the server's custom payload handler; {@code true} when Plexus Mod Loader consumed the payload. */
    static boolean receiveOnServer(ServerGamePacketListenerImpl listener, ServerboundCustomPayloadPacket packet) {
        if (!(packet.payload() instanceof PlexusPayload payload)) {
            return false;
        }
        ChannelEndpoint endpoint = LayerState.channels().get(Views.id(payload.id()));
        if (endpoint != null) {
            ServerPlayer player = listener.player;
            player.level().getServer().execute(() -> endpoint.receiveOnServer(payload.data(), Views.player(player)));
        }
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
            serverPlayer.connection.send(new ClientboundCustomPayloadPacket(new PlexusPayload(type(channel), data)));
        }

        @Override
        public void sendToServer(ResourceId channel, byte[] data) {
            if (side != GameSide.CLIENT) {
                throw new IllegalStateException("sendToServer is only available on the client");
            }
            dev.plexusmodloader.game.layer.v1_20_4.client.ClientHooks.sendToServer(
                    new ServerboundCustomPayloadPacket(new PlexusPayload(type(channel), data)));
        }
    }
}
