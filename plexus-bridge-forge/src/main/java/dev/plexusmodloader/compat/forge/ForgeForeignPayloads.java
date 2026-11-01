package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.compat.bridge.ForeignPayloads;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MinecraftForge's side of other loaders' custom payloads on one connection (ADR-0026 40, {@link ForeignPayloads}), when
 * Fabric or Quilt mods run with Forge.
 *
 * <p>Forge sends every payload of its channels as a {@code ForgePayload} (id, bytes, encoder), written by the codec
 * {@code ForgeHooks.getCustomPayloadCodec(id, maxSize)} gives for an id one of its channels has ({@code
 * NetworkRegistry.findTarget(id)}). Fabric API's networking registers the plugin-channel ids both loaders speak ({@code
 * minecraft:register}, {@code minecraft:unregister}) and its codec comes first in {@code CustomPacketPayload}'s codec,
 * so Forge's channel list ({@code ChannelListManager}, channel {@code minecraft:register}) was written with Fabric
 * API's codec and the connection broke when a player joined ({@code ClassCastException: ForgePayload cannot be cast to
 * RegistrationPayload}). A {@code ForgePayload} is written with Forge's codec ({@link Host#encoderFor}; the wire format
 * of the plugin channels is the same). Forge has no per-connection channel checks that refuse other loaders' payloads,
 * so this host has no rules. Checked with {@code javap} on Forge 26.2-65.1.5's and 26.3-66.1.0's universal jars.
 *
 * <p>Thread: {@link #install} on the launching thread; the host's answers from network threads.
 */
final class ForgeForeignPayloads {

    static final String FORGE_PAYLOAD = "net.minecraftforge.network.ForgePayload";
    private static final String FORGE_HOOKS = "net.minecraftforge.common.ForgeHooks";
    private static final String NETWORK_REGISTRY = "net.minecraftforge.network.NetworkRegistry";
    private static final String IDENTIFIER = "net.minecraft.resources.Identifier";

    private ForgeForeignPayloads() {}

    /** Installs Forge's view of its own payloads, from the classes in {@code game}. */
    static void install(ClassLoader game) {
        ForeignPayloads.installHost(new Host(game));
    }

    /** Forge's payloads: its {@code ForgePayload} objects, for ids its channels have. */
    static final class Host implements ForeignPayloads.Host {

        private final ClassLoader game;
        private final Map<Object, Object> codecs = new ConcurrentHashMap<>();
        private volatile Methods methods;

        Host(ClassLoader game) {
            this.game = game;
        }

        @Override
        public Object encoderFor(Object payload, Object chosen) {
            if (!forgesOwn(payload)) {
                return chosen;
            }
            Methods forge = methods();
            Object id = forge.invoke(forge.id, payload);
            if (forge.invoke(forge.findTarget, null, id) == null) {
                // Without a channel, Forge's own codec for the id is the discarding one: the game's choice stands.
                return chosen;
            }
            // Encoding ignores the size limit; it bounds decoding only.
            return codecs.computeIfAbsent(id, key -> forge.invoke(forge.payloadCodec, null, key, Integer.MAX_VALUE));
        }

        @Override
        public boolean owns(Object payload, Object protocol) {
            return forgesOwn(payload);
        }

        private static boolean forgesOwn(Object payload) {
            return payload.getClass().getName().equals(FORGE_PAYLOAD);
        }

        private Methods methods() {
            Methods value = methods;
            if (value == null) {
                value = new Methods(game);
                methods = value;
            }
            return value;
        }
    }

    /** The Forge methods the host calls, looked up once in the game's class loader. */
    private record Methods(Method id, Method findTarget, Method payloadCodec) {

        Methods(ClassLoader game) {
            this(
                    method(game, FORGE_PAYLOAD, "id"),
                    method(game, NETWORK_REGISTRY, "findTarget", IDENTIFIER),
                    method(game, FORGE_HOOKS, "getCustomPayloadCodec", IDENTIFIER, "int"));
        }

        Object invoke(Method method, Object receiver, Object... arguments) {
            try {
                return method.invoke(receiver, arguments);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Forge's " + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                                + " failed for a custom payload (" + e + ")",
                        e);
            }
        }

        private static Method method(ClassLoader game, String owner, String name, String... parameters) {
            try {
                Class<?> type = Class.forName(owner, false, game);
                Class<?>[] types = new Class<?>[parameters.length];
                for (int i = 0; i < parameters.length; i++) {
                    types[i] = parameters[i].equals("int") ? int.class : Class.forName(parameters[i], false, game);
                }
                return type.getMethod(name, types);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Forge's " + owner + "." + name + " is not there to write Forge's custom payloads (" + e + ")",
                        e);
            }
        }
    }
}
