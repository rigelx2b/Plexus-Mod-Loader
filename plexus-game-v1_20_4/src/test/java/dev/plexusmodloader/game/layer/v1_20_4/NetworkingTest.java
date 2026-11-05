package dev.plexusmodloader.game.layer.v1_20_4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.game.layer.v1_20_4.Networking.PlexusPayload;
import dev.plexusmodloader.game.version.layer.ChannelEndpoint;
import dev.plexusmodloader.game.version.layer.ConfigurationEndpoint;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A declared channel's payload goes through the game's packet bytes in 1.20.4: {@code <channel id> + data}. The
 * packets' own decoding is replaced by the {@code readPayload} hook, so these tests write a packet the way the game
 * does and read it back through the hook.
 */
class NetworkingTest {

    private static final ResourceId CHANNEL = ResourceId.of("demo", "ping");
    private static final ResourceId EXCHANGE = ResourceId.of("demo", "handshake");

    @BeforeAll
    static void startLayerWithChannels() {
        LayerTestSupport.bootstrap();
        ChannelEndpoint channel = new ChannelEndpoint() {
            @Override
            public ResourceId id() {
                return CHANNEL;
            }

            @Override
            public String ownerModId() {
                return "demo";
            }

            @Override
            public void receiveOnServer(byte[] data, PlayerView sender) {}

            @Override
            public void receiveOnClient(byte[] data) {}
        };
        ConfigurationEndpoint exchange = new ConfigurationEndpoint() {
            @Override
            public ResourceId id() {
                return EXCHANGE;
            }

            @Override
            public String ownerModId() {
                return "demo";
            }

            @Override
            public Optional<byte[]> request(UUID playerId, String playerName) {
                return Optional.empty();
            }

            @Override
            public Optional<byte[]> answer(byte[] request) {
                return Optional.empty();
            }

            @Override
            public Optional<String> check(UUID playerId, String playerName, Optional<byte[]> reply) {
                return Optional.empty();
            }

            @Override
            public int timeoutTicks() {
                return 20;
            }
        };
        LayerTestSupport.startLayer(LayerTestSupport.declarations(
                Map.of("channels", List.of(channel), "configurationExchanges", List.of(exchange))));
    }

    @Test
    void aDeclaredChannelPayloadSurvivesTheServerboundPacketBytes() {
        byte[] data = {1, 2, 3, 4, 5};
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        new ServerboundCustomPayloadPacket(new PlexusPayload(Networking.type(CHANNEL), data)).write(buffer);

        ResourceLocation id = buffer.readResourceLocation();
        assertEquals(new ResourceLocation("demo", "ping"), id);
        CustomPacketPayload read = Networking.readPayload(id, buffer);

        PlexusPayload payload = assertInstanceOf(PlexusPayload.class, read);
        assertEquals(id, payload.id());
        assertArrayEquals(data, payload.data());
        assertEquals(0, buffer.readableBytes(), "the payload takes every remaining byte");
    }

    @Test
    void aConfigurationExchangePayloadIsReadFromTheClientboundPacketBytes() {
        byte[] data = "request".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        new ClientboundCustomPayloadPacket(new PlexusPayload(Networking.type(EXCHANGE), data)).write(buffer);

        ResourceLocation id = buffer.readResourceLocation();
        PlexusPayload payload = assertInstanceOf(PlexusPayload.class, Networking.readPayload(id, buffer));
        assertArrayEquals(data, payload.data());
    }

    @Test
    void theGamesOwnAndUnknownPayloadsAreLeftToTheGame() {
        FriendlyByteBuf brand = new FriendlyByteBuf(Unpooled.buffer());
        brand.writeUtf("vanilla");
        assertNull(Networking.readPayload(new ResourceLocation("minecraft", "brand"), brand));
        assertEquals(8, brand.readableBytes(), "nothing was consumed");

        FriendlyByteBuf unknown = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[] {9}));
        assertNull(Networking.readPayload(new ResourceLocation("other", "channel"), unknown));
        assertEquals(1, unknown.readableBytes());
    }
}
