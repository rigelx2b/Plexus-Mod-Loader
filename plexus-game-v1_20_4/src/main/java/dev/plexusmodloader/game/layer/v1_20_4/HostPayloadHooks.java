package dev.plexusmodloader.game.layer.v1_20_4;

import dev.plexusmodloader.compat.bridge.ForeignPayloads;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom payloads of NeoForge 20.4 and another loader on one connection (ADR-0026 40, {@link ForeignPayloads}): NeoForge
 * 20.4's channel checks ({@code NetworkRegistry.canSendPacket}, {@code onModdedPacketAtServer}, {@code
 * onModdedPacketAtClient}) start with these hooks, and its filter of a bundle's packets ({@code filterGameBundlePackets})
 * ends with {@link #keepOtherLoadersBundledPayloads} (rules of the NeoForge 20.4 host). They leave other loaders'
 * payloads to those loaders: NeoForge knows only its own channels, refused to send Fabric API's {@code
 * minecraft:register} and disconnected the player on a Fabric payload no receiver took. Without a host, none of the
 * rules exists. Each hook receives NeoForge's {@code NetworkRegistry} (the rules target its instance methods) and does
 * not use it.
 *
 * <p>Thread: the connection's network thread or the game thread, as the game calls them.
 */
public final class HostPayloadHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");
    private static final AtomicBoolean DROP_REPORTED = new AtomicBoolean();

    private HostPayloadHooks() {}

    /** Head of the server's send check: true (sent) for another loader's payload, null runs the check. */
    public static Boolean otherLoadersClientboundPayload(
            NetworkRegistry registry, Packet<?> packet, ServerCommonPacketListener listener) {
        return packet instanceof ClientboundCustomPayloadPacket custom
                        && ForeignPayloads.foreign(custom.payload(), listener.protocol())
                ? Boolean.TRUE
                : null;
    }

    /** Head of the client's send check: true (sent) for another loader's payload, null runs the check. */
    public static Boolean otherLoadersServerboundPayload(
            NetworkRegistry registry, Packet<?> packet, ClientCommonPacketListener listener) {
        return packet instanceof ServerboundCustomPayloadPacket custom
                        && ForeignPayloads.foreign(custom.payload(), listener.protocol())
                ? Boolean.TRUE
                : null;
    }

    /**
     * Head of the server's handling of a modded payload no other loader took: true drops another loader's payload, as
     * that loader does with a payload it has no receiver for, where NeoForge would disconnect.
     */
    public static boolean dropOtherLoadersPayload(
            NetworkRegistry registry, ServerCommonPacketListener listener, ServerboundCustomPayloadPacket packet) {
        return dropped(packet.payload(), listener.protocol());
    }

    /** As {@link #dropOtherLoadersPayload}, on the client: true (handled) drops it, null runs NeoForge's handling. */
    public static Boolean dropOtherLoadersPayload(
            NetworkRegistry registry, ClientCommonPacketListener listener, ClientboundCustomPayloadPacket packet) {
        return dropped(packet.payload(), listener.protocol()) ? Boolean.TRUE : null;
    }

    /**
     * Before NeoForge's filter of a bundle's packets returns ({@code kept}, from {@code packets}): puts back the
     * play-phase payloads of other loaders that it left out, in their places. The filter only leaves packets out, in
     * order; when {@code kept} is not such a subsequence of {@code packets}, it is returned as it is.
     */
    public static List<Packet<?>> keepOtherLoadersBundledPayloads(
            NetworkRegistry registry,
            ChannelHandlerContext context,
            Iterable<Packet<?>> packets,
            List<Packet<?>> kept) {
        List<Packet<?>> result = new ArrayList<>();
        int next = 0;
        boolean restored = false;
        for (Packet<?> packet : packets) {
            if (next < kept.size() && kept.get(next) == packet) {
                result.add(packet);
                next++;
            } else if (packet instanceof ClientboundCustomPayloadPacket custom
                    && ForeignPayloads.foreign(custom.payload(), ConnectionProtocol.PLAY)) {
                result.add(packet);
                restored = true;
            }
        }
        return restored && next == kept.size() ? result : kept;
    }

    private static boolean dropped(CustomPacketPayload payload, ConnectionProtocol protocol) {
        if (!ForeignPayloads.foreign(payload, protocol)) {
            return false;
        }
        if (DROP_REPORTED.compareAndSet(false, true)) {
            LOGGER.info(
                    "[Plexus Mod Loader] Payload {} of another loader had no receiver; it is dropped, as that loader"
                            + " does (ADR-0026 40)",
                    payload.id());
        }
        return true;
    }
}
