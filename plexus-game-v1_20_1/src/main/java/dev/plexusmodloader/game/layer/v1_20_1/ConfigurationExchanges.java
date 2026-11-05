package dev.plexusmodloader.game.layer.v1_20_1;

import com.mojang.authlib.GameProfile;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.game.version.layer.ConfigurationEndpoint;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs mods' configuration exchanges during login. Minecraft 1.20.1 has no configuration phase (1.20.2 added it), so
 * an exchange is a login query: {@code handleAcceptedLogin} is held, one tick at a time, while each exchange sends its
 * request, waits for the client's answer or the timeout, and then lets the player go on or refuses the connection. A
 * client that does not know the channel answers a query with no data, which counts as no answer.
 *
 * <p>The game ends a login after 600 ticks whatever is running, so a mod's timeouts together must stay under that.
 */
final class ConfigurationExchanges {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");
    /** Above any query number the game itself uses; the game sends none, but other mods might. */
    private static final int FIRST_TRANSACTION = 0x504C0000;

    private static final Map<ServerLoginPacketListenerImpl, Login> LOGINS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ConfigurationExchanges() {}

    /**
     * Head of {@code ServerLoginPacketListenerImpl.handleAcceptedLogin()}, which the listener's tick calls until the
     * login moves on; {@code true} keeps the player waiting for this tick.
     */
    static boolean holdLogin(ServerLoginPacketListenerImpl listener) {
        Map<ResourceId, ConfigurationEndpoint> exchanges = LayerState.configurationExchanges();
        if (exchanges.isEmpty()) {
            return false;
        }
        Login login = LOGINS.computeIfAbsent(listener, key -> new Login(key, exchanges));
        boolean waiting = login.poll();
        if (!waiting) {
            LOGINS.remove(listener);
        }
        return waiting;
    }

    /** Head of {@code handleCustomQueryPacket}; {@code true} when the packet answers this login's exchange. */
    static boolean receive(ServerLoginPacketListenerImpl listener, ServerboundCustomQueryPacket packet) {
        Login login = LOGINS.get(listener);
        return login != null && login.answer(packet);
    }

    private static final class Login {

        private final ServerLoginPacketListenerImpl listener;
        private final Queue<ConfigurationEndpoint> pending;
        private final AtomicReference<Answer> answer = new AtomicReference<>();
        private ConfigurationEndpoint current;
        private int transaction = FIRST_TRANSACTION;
        private int waited;

        Login(ServerLoginPacketListenerImpl listener, Map<ResourceId, ConfigurationEndpoint> exchanges) {
            this.listener = listener;
            this.pending = new ArrayDeque<>(exchanges.values());
        }

        /** Runs on the server thread, once per tick of the login; {@code true} while an exchange is still running. */
        boolean poll() {
            GameProfile profile = read(listener, "gameProfile", GameProfile.class);
            String name = profile.getName();
            UUID id = profile.getId() != null ? profile.getId() : UUIDUtil.createOfflinePlayerUUID(name);
            while (true) {
                if (current == null) {
                    current = pending.poll();
                    if (current == null) {
                        return false;
                    }
                    Optional<byte[]> request = current.request(id, name);
                    if (request.isEmpty()) {
                        // Nothing to ask this player.
                        current = null;
                        continue;
                    }
                    waited = 0;
                    answer.set(null);
                    transaction++;
                    read(listener, "connection", Connection.class)
                            .send(new ClientboundCustomQueryPacket(
                                    transaction, Networking.type(current.id()), Networking.buffer(request.get())));
                    return true;
                }
                Answer reply = answer.get();
                if (reply == null && ++waited < current.timeoutTicks()) {
                    return true;
                }
                Optional<byte[]> data = reply == null ? Optional.empty() : Optional.ofNullable(reply.data());
                Optional<String> refusal = current.check(id, name, data);
                LOGGER.info(
                        "[Plexus Mod Loader] Configuration exchange {} with {}: {}, {}",
                        current.id(),
                        name,
                        data.isEmpty() ? "no answer" : "answered",
                        refusal.isEmpty() ? "accepted" : "refused");
                current = null;
                if (refusal.isPresent()) {
                    listener.disconnect(Component.literal(refusal.get()));
                    return true;
                }
            }
        }

        /** Runs on the connection's thread: the game's login handler does not move to the server thread. */
        boolean answer(ServerboundCustomQueryPacket packet) {
            if (packet.getTransactionId() != transaction) {
                return false;
            }
            FriendlyByteBuf buffer = packet.getData();
            if (buffer == null) {
                answer.set(new Answer(null));
                return true;
            }
            try {
                answer.set(new Answer(Networking.bytes(buffer)));
            } finally {
                buffer.release();
            }
            return true;
        }
    }

    /** What the client sent: {@code data} is null when it did not know the channel. */
    private record Answer(byte[] data) {}

    /** The listener keeps the profile and connection in package-private fields without accessors. */
    private static <T> T read(ServerLoginPacketListenerImpl listener, String name, Class<T> type) {
        try {
            Field field = ServerLoginPacketListenerImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(listener));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PLEXUS-NETWORK-005 cannot read the login's " + name, e);
        }
    }
}
