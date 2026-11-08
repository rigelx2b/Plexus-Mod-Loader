package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.compat.bridge.ForeignPayloads;
import dev.plexusmodloader.internal.transform.HeadHookResult;
import dev.plexusmodloader.internal.transform.InvokeHookAtHead;
import dev.plexusmodloader.internal.transform.InvokeHookBeforeReturn;
import dev.plexusmodloader.internal.transform.MethodTarget;
import dev.plexusmodloader.internal.transform.MethodTransformAction;
import dev.plexusmodloader.internal.transform.TransformFailurePolicy;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NeoForge 20.4's side of other loaders' custom payloads on one connection (ADR-0026 40, {@link ForeignPayloads}), when
 * Fabric or Quilt mods run with NeoForge 20.4's mods on FML 2 ({@link NeoForgeFml4GameHost}). {@link
 * NeoForgeForeignPayloads} does the same for the NeoForge releases without ModLauncher, whose {@code NetworkRegistry}
 * has other methods.
 *
 * <p>NeoForge 20.4's channel checks know only the channels NeoForge negotiated: its send check refused Fabric API's
 * {@code minecraft:register} ({@code Tried to send minecraft:register packet to a client that does not support it}),
 * and its handling of a payload that Fabric API's networking had no receiver for disconnected the player ({@code
 * Incompatible client! Please use NeoForge 20.4.253}) where Fabric Loader drops it. The rules ({@link #rules}) leave
 * other loaders' payloads to those loaders. Checked with {@code javap} on NeoForge 20.4.253's universal jar: {@code
 * NetworkRegistry.canSendPacket(Packet, ServerCommonPacketListener)Z} and {@code (Packet, ClientCommonPacketListener)Z},
 * {@code onModdedPacketAtServer(ServerCommonPacketListener, ServerboundCustomPayloadPacket)V} and {@code
 * onModdedPacketAtClient(ClientCommonPacketListener, ClientboundCustomPayloadPacket)Z} (the client's {@code
 * ClientPacketListener.handleCustomPayload} returns when it is true), {@code filterGameBundlePackets(ChannelHandlerContext,
 * Iterable)List} (it writes no parameter slot), and the instance maps {@code knownConfigurationRegistrations} and {@code
 * knownPlayRegistrations} (payload id to registration) of {@code INSTANCE}. NeoForge's built-in payloads are its own
 * classes.
 *
 * <p>Thread: {@link #install} on the launching thread; the host's answers from network threads.
 */
final class NeoForgeFml2ForeignPayloads {

    static final String NETWORK_REGISTRY = NeoForgeForeignPayloads.NETWORK_REGISTRY;
    private static final String PACKET = "Lnet/minecraft/network/protocol/Packet;";
    private static final String SERVER_LISTENER = "Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;";
    private static final String CLIENT_LISTENER = "Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;";
    private static final String SERVERBOUND = "Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;";
    private static final String CLIENTBOUND = "Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;";

    private NeoForgeFml2ForeignPayloads() {}

    /** The rules for {@code gameVersion}, with hooks in {@code gameLayerPackage}'s {@code HostPayloadHooks}. */
    static List<TransformRuleDefinition> rules(String gameVersion, String gameLayerPackage) {
        String hooks = gameLayerPackage + "/HostPayloadHooks";
        Set<GameSide> bothSides = Set.of(GameSide.CLIENT, GameSide.DEDICATED_SERVER);
        return List.of(
                rule(
                        "plexus:neoforge20_send_check_clientbound",
                        gameVersion,
                        bothSides,
                        new MethodTarget(NETWORK_REGISTRY, "canSendPacket", "(" + PACKET + SERVER_LISTENER + ")Z"),
                        new InvokeHookAtHead(
                                hooks, "otherLoadersClientboundPayload", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:neoforge20_send_check_serverbound",
                        gameVersion,
                        Set.of(GameSide.CLIENT),
                        new MethodTarget(NETWORK_REGISTRY, "canSendPacket", "(" + PACKET + CLIENT_LISTENER + ")Z"),
                        new InvokeHookAtHead(
                                hooks, "otherLoadersServerboundPayload", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:neoforge20_modded_payload_server",
                        gameVersion,
                        bothSides,
                        new MethodTarget(
                                NETWORK_REGISTRY, "onModdedPacketAtServer", "(" + SERVER_LISTENER + SERVERBOUND + ")V"),
                        new InvokeHookAtHead(hooks, "dropOtherLoadersPayload", HeadHookResult.CANCEL_IF_TRUE)),
                rule(
                        "plexus:neoforge20_modded_payload_client",
                        gameVersion,
                        Set.of(GameSide.CLIENT),
                        new MethodTarget(
                                NETWORK_REGISTRY, "onModdedPacketAtClient", "(" + CLIENT_LISTENER + CLIENTBOUND + ")Z"),
                        new InvokeHookAtHead(hooks, "dropOtherLoadersPayload", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:neoforge20_bundle_filter",
                        gameVersion,
                        bothSides,
                        new MethodTarget(
                                NETWORK_REGISTRY,
                                "filterGameBundlePackets",
                                "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Iterable;)Ljava/util/List;"),
                        new InvokeHookBeforeReturn(hooks, "keepOtherLoadersBundledPayloads")));
    }

    private static TransformRuleDefinition rule(
            String id, String gameVersion, Set<GameSide> sides, MethodTarget target, MethodTransformAction action) {
        return new TransformRuleDefinition(
                id, "plexus", gameVersion, sides, target, action, TransformFailurePolicy.DISABLE_RULE);
    }

    /**
     * Installs NeoForge 20.4's view of its own payloads, read from {@code NetworkRegistry.INSTANCE} in {@code game} when
     * first asked (its maps are filled as NeoForge's mods register, before any connection).
     */
    static void install(ClassLoader game) {
        ForeignPayloads.installHost(new Host(game));
    }

    /**
     * Whether a payload of class {@code payloadClass} with id {@code id}, in the phase {@code protocol}, is NeoForge's:
     * one of NeoForge's own payload classes, or an id NeoForge's mods registered for that phase.
     */
    static boolean owns(String payloadClass, Object id, String protocol, Map<?, ?> configuration, Map<?, ?> play) {
        if (payloadClass.startsWith("net.neoforged.neoforge.")) {
            return true;
        }
        return switch (protocol) {
            case "CONFIGURATION" -> configuration.containsKey(id);
            case "PLAY" -> play.containsKey(id);
            default -> false;
        };
    }

    /** NeoForge 20.4's payloads. */
    private static final class Host implements ForeignPayloads.Host {

        /** Each payload class's {@code id()}, looked up once. */
        private static final ClassValue<Method> ID = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                try {
                    Method accessor = type.getMethod("id");
                    accessor.setAccessible(true);
                    return accessor;
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException(type + " has no " + e.getMessage(), e);
                }
            }
        };

        private final ClassLoader game;
        private volatile Map<?, ?> configuration;
        private volatile Map<?, ?> play;

        Host(ClassLoader game) {
            this.game = game;
        }

        /** NeoForge 20.4 payloads write themselves; the game's choice stands. */
        @Override
        public Object encoderFor(Object payload, Object chosen) {
            return chosen;
        }

        @Override
        public boolean owns(Object payload, Object protocol) {
            if (play == null) {
                read();
            }
            String phase = protocol instanceof Enum<?> constant ? constant.name() : String.valueOf(protocol);
            return NeoForgeFml2ForeignPayloads.owns(
                    payload.getClass().getName(), id(payload), phase, configuration, play);
        }

        private static Object id(Object payload) {
            try {
                return ID.get(payload.getClass()).invoke(payload);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("A custom payload without CustomPacketPayload.id(): " + payload, e);
            }
        }

        private synchronized void read() {
            if (play != null) {
                return;
            }
            try {
                Class<?> registry = Class.forName(NETWORK_REGISTRY.replace('/', '.'), true, game);
                Field instance = registry.getDeclaredField("INSTANCE");
                instance.setAccessible(true);
                Object registryInstance = instance.get(null);
                configuration = map(registry, registryInstance, "knownConfigurationRegistrations");
                play = map(registry, registryInstance, "knownPlayRegistrations");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("NeoForge's NetworkRegistry is not readable (" + e + ")", e);
            }
        }

        private static Map<?, ?> map(Class<?> registry, Object instance, String name)
                throws ReflectiveOperationException {
            Field field = registry.getDeclaredField(name);
            field.setAccessible(true);
            return (Map<?, ?>) field.get(instance);
        }
    }
}
