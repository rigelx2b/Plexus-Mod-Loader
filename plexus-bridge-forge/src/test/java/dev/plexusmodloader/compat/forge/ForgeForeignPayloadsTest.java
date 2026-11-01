package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.compat.bridge.ForeignPayloads;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraftforge.network.ForgePayload;
import net.minecraftforge.network.NetworkRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0026 40: Fabric API's networking and MinecraftForge's on one connection. Fabric API's codec owns {@code
 * minecraft:register}; before, Forge's channel list on that id was written with it and a player could not join ({@code
 * ForgePayload cannot be cast to RegistrationPayload}). Stand-ins for Forge's {@code ForgePayload}, {@code
 * NetworkRegistry} and {@code ForgeHooks}.
 */
class ForgeForeignPayloadsTest {

    private static final Object FABRIC_CODEC = "Fabric API's minecraft:register codec";
    private static final Identifier REGISTER = new Identifier("minecraft:register");

    /** Fabric API's own object for {@code minecraft:register}. */
    private record FabricRegistration() implements CustomPacketPayload {
        @Override
        public Type type() {
            return new Type("minecraft:register");
        }
    }

    @BeforeEach
    void installHost() {
        NetworkRegistry.channels().clear();
        NetworkRegistry.channels().put(REGISTER, "Forge's ChannelListManager");
        ForgeForeignPayloads.install(ForgeForeignPayloadsTest.class.getClassLoader());
    }

    @Test
    void forgesPayloadOnAnIdFabricOwnsIsWrittenWithForgesCodec() {
        Object codec = ForeignPayloads.encoderFor(new ForgePayload(REGISTER), FABRIC_CODEC);

        assertEquals("Forge's codec for minecraft:register", codec);
        assertSame(codec, ForeignPayloads.encoderFor(new ForgePayload(REGISTER), FABRIC_CODEC));
    }

    @Test
    void otherLoadersPayloadsKeepTheGamesChoice() {
        assertSame(FABRIC_CODEC, ForeignPayloads.encoderFor(new FabricRegistration(), FABRIC_CODEC));
    }

    @Test
    void forgesPayloadWithoutAChannelKeepsTheGamesChoice() {
        Object chosen = "the game's codec";

        assertSame(chosen, ForeignPayloads.encoderFor(new ForgePayload(new Identifier("forge_hello:none")), chosen));
    }

    @Test
    void onlyForgesPayloadsAreForgesOwn() {
        assertFalse(ForeignPayloads.foreign(new ForgePayload(REGISTER), "PLAY"));
        assertTrue(ForeignPayloads.foreign(new FabricRegistration(), "PLAY"));
    }
}
