package dev.plexusmodloader.game.layer.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.plexusmodloader.api.resource.ResourceId;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A declared channel's payload goes through the game's packet bytes in 1.20.1: {@code <channel id> + data}, and a
 * configuration exchange through the login query packets. These tests write a packet the way the game does and read it
 * back through the game's own decoding.
 */
class NetworkingTest {

    private static final ResourceId CHANNEL = ResourceId.of("demo", "ping");

    @BeforeAll
    static void bootstrap() {
        LayerTestSupport.bootstrap();
    }

    @Test
    void aPayloadSurvivesTheServerboundPacketBytes() {
        byte[] data = {1, 2, 3, 4, 5};
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        new ServerboundCustomPayloadPacket(Networking.type(CHANNEL), Networking.buffer(data)).write(wire);

        ServerboundCustomPayloadPacket read = new ServerboundCustomPayloadPacket(wire);
        assertEquals(new ResourceLocation("demo", "ping"), read.getIdentifier());
        assertArrayEquals(data, Networking.bytes(read.getData()));
    }

    /**
     * {@code ServerboundCustomPayloadPacket.handle} releases the packet's buffer after the handler, and getData() hands
     * out that same buffer, so reading the bytes must not release it: a second release drops the connection.
     */
    @Test
    void readingAServerboundPayloadLeavesTheBufferToThePacket() {
        ServerboundCustomPayloadPacket packet =
                new ServerboundCustomPayloadPacket(Networking.type(CHANNEL), Networking.buffer(new byte[] {1, 2}));
        assertArrayEquals(new byte[] {1, 2}, Networking.bytes(packet.getData()));
        assertEquals(1, packet.getData().refCnt());
    }

    @Test
    void aPayloadSurvivesTheClientboundPacketBytes() {
        byte[] data = {6, 7, 8};
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        new ClientboundCustomPayloadPacket(Networking.type(CHANNEL), Networking.buffer(data)).write(wire);

        ClientboundCustomPayloadPacket read = new ClientboundCustomPayloadPacket(wire);
        assertEquals(new ResourceLocation("demo", "ping"), read.getIdentifier());
        assertArrayEquals(data, Networking.bytes(read.getData()));
    }

    @Test
    void anExchangeRequestSurvivesTheLoginQueryBytes() {
        byte[] data = "request".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        new ClientboundCustomQueryPacket(41, Networking.type(CHANNEL), Networking.buffer(data)).write(wire);

        ClientboundCustomQueryPacket read = new ClientboundCustomQueryPacket(wire);
        assertEquals(41, read.getTransactionId());
        assertEquals(new ResourceLocation("demo", "ping"), read.getIdentifier());
        assertArrayEquals(data, Networking.bytes(read.getData()));
    }

    @Test
    void anAnswerWithoutDataMeansTheClientDidNotKnowTheChannel() {
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        new ServerboundCustomQueryPacket(41, null).write(wire);

        ServerboundCustomQueryPacket read = new ServerboundCustomQueryPacket(wire);
        assertEquals(41, read.getTransactionId());
        assertNull(read.getData());
    }

    @Test
    void readingBytesLeavesTheBufferAsItIs() {
        FriendlyByteBuf buffer = Networking.buffer(new byte[] {9, 9});
        assertArrayEquals(new byte[] {9, 9}, Networking.bytes(buffer));
        assertEquals(2, buffer.readableBytes());
    }
}
