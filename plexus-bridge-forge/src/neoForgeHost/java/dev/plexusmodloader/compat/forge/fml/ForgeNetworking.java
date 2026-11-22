package dev.plexusmodloader.compat.forge.fml;

import dev.plexusmodloader.compat.bridge.HostChannels;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;

/**
 * Forge on NeoForge, F-N6 networking (ADR-0051 decision 22): Forge mods' channels carried over NeoForge's own payloads.
 * Forge's channel classes build, encode, decode and handle the messages; a play-phase send to a {@code
 * PacketDistributor} target already makes a vanilla custom payload packet with Forge's {@code ForgePayload}, whose type
 * is the channel's name. On NeoForge's {@code RegisterPayloadHandlersEvent}, once for all Forge mods, this registers each
 * Forge channel's name as an optional NeoForge payload type of both directions, with a codec of {@code ForgePayload}'s
 * bytes and a handler on the network thread that passes the payload to the channel as Forge's own {@code
 * ForgeHooks.onCustomPayload} does: Forge's {@code CustomPayloadEvent} with its context for the connection, to {@code
 * NetworkInstance.dispatch}. Forge's work queues ({@code LogicalSidedProvider}, read by {@code Context.enqueueWork}) get
 * the client and NeoForge's current server through Forge's public setters ({@link #installWorkQueues}).
 *
 * <p>NeoForge's and Minecraft's classes are in FML's game layer and are used through {@link MethodHandles#publicLookup()}
 * and {@link MethodHandleProxies}, as the host is not compiled against them (see {@link ForgeGameEvents}). Forge's
 * classes take or return Minecraft's types, so they are reached the same way.
 *
 * <p>Thread: {@link #listen} on FML's construct stage; registration on the thread NeoForge posts its event on; {@link
 * #handle} on the connection's network thread.
 */
final class ForgeNetworking {

    private static final String REGISTER_EVENT = "net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent";
    private static final String CLIENT_REGISTER_EVENT =
            "net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent";
    private static final String REGISTRAR = "net.neoforged.neoforge.network.registration.PayloadRegistrar";
    private static final String HANDLER_THREAD = "net.neoforged.neoforge.network.registration.HandlerThread";
    private static final String PAYLOAD_HANDLER = "net.neoforged.neoforge.network.handling.IPayloadHandler";
    private static final String PAYLOAD_CONTEXT = "net.neoforged.neoforge.network.handling.IPayloadContext";
    private static final String PAYLOAD = "net.minecraft.network.protocol.common.custom.CustomPacketPayload";
    private static final String PAYLOAD_TYPE = PAYLOAD + "$Type";
    private static final String STREAM_CODEC = "net.minecraft.network.codec.StreamCodec";
    private static final String STREAM_ENCODER = "net.minecraft.network.codec.StreamEncoder";
    private static final String STREAM_DECODER = "net.minecraft.network.codec.StreamDecoder";
    private static final String FRIENDLY_BYTE_BUF = "net.minecraft.network.FriendlyByteBuf";
    private static final String IDENTIFIER = "net.minecraft.resources.Identifier";
    private static final String CONNECTION = "net.minecraft.network.Connection";
    private static final String BYTE_BUF = "io.netty.buffer.ByteBuf";
    private static final String UNPOOLED = "io.netty.buffer.Unpooled";
    private static final String FORGE_PAYLOAD = "net.minecraftforge.network.ForgePayload";
    private static final String FORGE_REGISTRY = "net.minecraftforge.network.NetworkRegistry";
    private static final String NETWORK_INSTANCE = "net.minecraftforge.network.NetworkInstance";
    private static final String FORGE_EVENT = "net.minecraftforge.event.network.CustomPayloadEvent";
    private static final String FORGE_CONTEXT = FORGE_EVENT + "$Context";
    private static final String SIDED_PROVIDER = "net.minecraftforge.common.util.LogicalSidedProvider";

    /** Namespaces whose Forge channels are Forge's own (its handshake), which nothing here carries. */
    private static final List<String> OWN_NAMESPACES = List.of("forge", "minecraft");

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicBoolean WORK_QUEUES = new AtomicBoolean();
    private static final AtomicBoolean CLIENT_REGISTERED = new AtomicBoolean();

    /** The payload types registered and their handlers, for the client's handlers of clientbound payloads. */
    private final List<Object[]> registered = new CopyOnWriteArrayList<>();

    private static volatile ForgeNetworking instance;

    private final MethodHandle registrar;
    private final MethodHandle optional;
    private final MethodHandle executesOn;
    private final Object networkThread;
    private final MethodHandle playBidirectional;
    private final MethodHandle newType;
    private final MethodHandle codecOf;
    private final Class<?> encoderInterface;
    private final Class<?> decoderInterface;
    private final Class<?> handlerInterface;
    private final MethodHandle newFriendlyByteBuf;
    private final MethodHandle readableBytes;
    private final MethodHandle readBytes;
    private final MethodHandle newBuffer;
    private final MethodHandle wrapBytes;
    private final MethodHandle createPayload;
    private final MethodHandle writePayload;
    private final MethodHandle payloadData;
    private final MethodHandle connection;
    private final MethodHandle newContext;
    private final MethodHandle newEvent;
    private final MethodHandle channels;
    private final MethodHandle findTarget;
    private final MethodHandle dispatch;
    private final MethodHandle namespace;

    private ForgeNetworking(ClassLoader game) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> registrarClass = load(REGISTRAR, game);
        Class<?> handlerThread = load(HANDLER_THREAD, game);
        Class<?> payloadType = load(PAYLOAD_TYPE, game);
        Class<?> streamCodec = load(STREAM_CODEC, game);
        Class<?> friendly = load(FRIENDLY_BYTE_BUF, game);
        Class<?> identifier = load(IDENTIFIER, game);
        Class<?> byteBuf = load(BYTE_BUF, game);
        Class<?> forgePayload = load(FORGE_PAYLOAD, game);
        Class<?> forgeContext = load(FORGE_CONTEXT, game);
        Class<?> forgeEvent = load(FORGE_EVENT, game);
        Class<?> networkInstance = load(NETWORK_INSTANCE, game);
        Class<?> connectionClass = load(CONNECTION, game);
        encoderInterface = load(STREAM_ENCODER, game);
        decoderInterface = load(STREAM_DECODER, game);
        handlerInterface = load(PAYLOAD_HANDLER, game);
        registrar = lookup.findVirtual(
                load(REGISTER_EVENT, game), "registrar", MethodType.methodType(registrarClass, String.class));
        optional = lookup.findVirtual(registrarClass, "optional", MethodType.methodType(registrarClass));
        executesOn =
                lookup.findVirtual(registrarClass, "executesOn", MethodType.methodType(registrarClass, handlerThread));
        networkThread = handlerThread.getField("NETWORK").get(null);
        playBidirectional = lookup.findVirtual(
                registrarClass,
                "playBidirectional",
                MethodType.methodType(registrarClass, payloadType, streamCodec, handlerInterface));
        newType = lookup.findConstructor(payloadType, MethodType.methodType(void.class, identifier));
        codecOf = lookup.findStatic(
                streamCodec, "of", MethodType.methodType(streamCodec, encoderInterface, decoderInterface));
        newFriendlyByteBuf = lookup.findConstructor(friendly, MethodType.methodType(void.class, byteBuf));
        readableBytes = lookup.findVirtual(byteBuf, "readableBytes", MethodType.methodType(int.class));
        readBytes = lookup.findVirtual(byteBuf, "readBytes", MethodType.methodType(byteBuf, byte[].class));
        Class<?> unpooled = load(UNPOOLED, game);
        newBuffer = lookup.findStatic(unpooled, "buffer", MethodType.methodType(byteBuf));
        wrapBytes = lookup.findStatic(unpooled, "wrappedBuffer", MethodType.methodType(byteBuf, byte[].class));
        createPayload =
                lookup.findStatic(forgePayload, "create", MethodType.methodType(forgePayload, identifier, friendly));
        writePayload = lookup.findVirtual(forgePayload, "write", MethodType.methodType(void.class, friendly));
        payloadData = lookup.findVirtual(forgePayload, "data", MethodType.methodType(friendly));
        connection =
                lookup.findVirtual(load(PAYLOAD_CONTEXT, game), "connection", MethodType.methodType(connectionClass));
        newContext = lookup.findConstructor(forgeContext, MethodType.methodType(void.class, connectionClass));
        newEvent = lookup.findConstructor(
                forgeEvent, MethodType.methodType(void.class, identifier, Object.class, forgeContext, int.class));
        Class<?> forgeRegistry = load(FORGE_REGISTRY, game);
        channels = lookup.findStatic(forgeRegistry, "buildChannelVersions", MethodType.methodType(Map.class));
        findTarget = lookup.findStatic(forgeRegistry, "findTarget", MethodType.methodType(networkInstance, identifier));
        dispatch = lookup.findVirtual(networkInstance, "dispatch", MethodType.methodType(boolean.class, forgeEvent));
        namespace = lookup.findVirtual(identifier, "getNamespace", MethodType.methodType(String.class));
    }

    private static Class<?> load(String name, ClassLoader game) throws ClassNotFoundException {
        return Class.forName(name, false, game);
    }

    /**
     * The networking of the game whose classes {@code game} loads, resolved once.
     *
     * @throws IllegalStateException when NeoForge's payload registration or Forge's channel classes are not as Plexus Mod
     *     Loader expects
     */
    static synchronized ForgeNetworking of(ClassLoader game) {
        ForgeNetworking current = instance;
        if (current != null) {
            return current;
        }
        try {
            current = new ForgeNetworking(game);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(
                    "NeoForge's payload registration and Forge's channel classes could not be resolved, so Forge mods'"
                            + " channels are not carried: " + e,
                    e);
        }
        instance = current;
        return current;
    }

    /**
     * Adds the listener that registers the Forge channels with NeoForge, on a Forge mod's {@code modBus}: the first
     * Forge mod to get NeoForge's event registers every Forge channel, which all Forge mods have made by then (NeoForge
     * posts it after common and sided setup).
     */
    void listen(IEventBus modBus, ClassLoader game, boolean client, Consumer<String> log) {
        modBus.addListener(eventClass(REGISTER_EVENT, game), event -> {
            if (REGISTERED.compareAndSet(false, true)) {
                register(event, log);
            }
        });
        if (!client) {
            return;
        }
        // NeoForge takes a client's handlers of clientbound payloads from its own client event, posted after the
        // registration above; without them its client stops ("Some clientbound payloads are missing client-side
        // handlers").
        MethodHandle clientRegister;
        try {
            clientRegister = MethodHandles.publicLookup()
                    .findVirtual(
                            load(CLIENT_REGISTER_EVENT, game),
                            "register",
                            MethodType.methodType(
                                    void.class, load(PAYLOAD_TYPE, game), networkThread.getClass(), handlerInterface));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NeoForge's " + CLIENT_REGISTER_EVENT + " is not as expected: " + e, e);
        }
        modBus.addListener(eventClass(CLIENT_REGISTER_EVENT, game), event -> {
            if (CLIENT_REGISTERED.compareAndSet(false, true)) {
                registerClient(event, clientRegister, log);
            }
        });
    }

    private static <T extends Event> Class<T> eventClass(String name, ClassLoader game) {
        try {
            @SuppressWarnings("unchecked")
            Class<T> found = (Class<T>) load(name, game).asSubclass(Event.class);
            return found;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("NeoForge's " + name + " is missing", e);
        }
    }

    /** Registers the client's handler of each payload type {@link #register} registered, on the network thread. */
    private void registerClient(Object event, MethodHandle clientRegister, Consumer<String> log) {
        try {
            for (Object[] entry : registered) {
                clientRegister.invoke(event, entry[0], networkThread, entry[1]);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Forge channels' client handlers could not be registered: " + e, e);
        }
        log.accept("Forge on NeoForge: client handlers for " + registered.size() + " Forge channel(s) (ADR-0051 22)");
    }

    /** Registers each Forge channel (but Forge's own) as a NeoForge payload type on {@code event}. */
    void register(Object event, Consumer<String> log) {
        List<String> names = new ArrayList<>();
        try {
            for (Map.Entry<Object, Object> entry : sorted((Map<?, ?>) channels.invoke())) {
                Object name = entry.getKey();
                if (OWN_NAMESPACES.contains((String) namespace.invoke(name))) {
                    continue;
                }
                Object payloadType = newType.invoke(name);
                Object codec = codecOf.invoke(
                        MethodHandleProxies.asInterfaceInstance(
                                encoderInterface, MethodHandles.insertArguments(ENCODE, 0, this)),
                        MethodHandleProxies.asInterfaceInstance(
                                decoderInterface, MethodHandles.insertArguments(DECODE, 0, this, name)));
                Object handler = MethodHandleProxies.asInterfaceInstance(
                        handlerInterface, MethodHandles.insertArguments(HANDLE, 0, this, name));
                Object registrarFor = executesOn.invoke(
                        optional.invoke(registrar.invoke(event, String.valueOf(entry.getValue()))), networkThread);
                playBidirectional.invoke(registrarFor, payloadType, codec, handler);
                registered.add(new Object[] {payloadType, handler});
                names.add(name.toString());
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Forge channels could not be registered with NeoForge: " + e, e);
        }
        log.accept("Forge on NeoForge: " + names.size() + " Forge channel(s) carried as NeoForge payloads " + names
                + " (ADR-0051 22)");
    }

    /** Forge's channels (name to protocol version), copied and ordered by name, so they register in a stable order. */
    private static List<Map.Entry<Object, Object>> sorted(Map<?, ?> versions) {
        List<Map.Entry<Object, Object>> entries = new ArrayList<>();
        versions.forEach((name, version) -> entries.add(new AbstractMap.SimpleImmutableEntry<>(name, version)));
        entries.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        return entries;
    }

    private static final MethodHandle ENCODE;
    private static final MethodHandle DECODE;
    private static final MethodHandle HANDLE;

    static {
        MethodHandles.Lookup own = MethodHandles.lookup();
        try {
            ENCODE = own.findVirtual(
                    ForgeNetworking.class, "encode", MethodType.methodType(void.class, Object.class, Object.class));
            DECODE = own.findVirtual(
                    ForgeNetworking.class, "decode", MethodType.methodType(Object.class, Object.class, Object.class));
            HANDLE = own.findVirtual(
                    ForgeNetworking.class,
                    "handle",
                    MethodType.methodType(void.class, Object.class, Object.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** The codec's write: Forge's payload writes itself ({@code ForgePayload.write}, which runs the channel's encoder). */
    void encode(Object buffer, Object payload) throws Throwable {
        writePayload.invoke(payload, buffer);
    }

    /** The codec's read: the rest of {@code buffer} is the payload of channel {@code name}, copied. */
    Object decode(Object name, Object buffer) throws Throwable {
        // Copied into a heap array: a connection's buffer may be pooled, and nothing releases the payload's buffer.
        byte[] bytes = new byte[(int) readableBytes.invoke(buffer)];
        readBytes.invoke(buffer, bytes);
        return createPayload.invoke(name, newFriendlyByteBuf.invoke(wrapBytes.invoke(bytes)));
    }

    /**
     * NeoForge's handler for channel {@code name}: Forge's receive path ({@code ForgeHooks.onCustomPayload}). A payload
     * that was never encoded (the in-memory connection of a single-player game passes the sender's object) is written
     * into bytes first, which Forge's channel reads.
     */
    void handle(Object name, Object payload, Object context) throws Throwable {
        Object received = payload;
        if (payloadData.invoke(payload) == null) {
            Object buffer = newFriendlyByteBuf.invoke(newBuffer.invoke());
            writePayload.invoke(payload, buffer);
            received = createPayload.invoke(name, buffer);
        }
        Object forgeContext = newContext.invoke(connection.invoke(context));
        Object event = newEvent.invoke(name, received, forgeContext, 0);
        Object target = findTarget.invoke(name);
        if (target != null) {
            boolean handled = (boolean) dispatch.invoke(target, event);
            if (!handled) {
                PlexusFml.log("Forge on NeoForge: a payload on Forge channel " + name + " was not handled");
            }
        }
    }

    /**
     * Sets Forge's work queues ({@code LogicalSidedProvider.setClient} and {@code setServer}, as Forge's own client
     * start and server start do), once: the client on a client, and NeoForge's current server, read on each call.
     *
     * @throws IllegalStateException when Forge's setters or NeoForge's server lookup are not as expected
     */
    static void installWorkQueues(ClassLoader game, boolean client) {
        if (!WORK_QUEUES.compareAndSet(false, true)) {
            return;
        }
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            Class<?> provider = load(SIDED_PROVIDER, game);
            MethodHandle currentServer = lookup.findStatic(
                    load("net.neoforged.neoforge.server.ServerLifecycleHooks", game),
                    "getCurrentServer",
                    MethodType.methodType(load("net.minecraft.server.MinecraftServer", game)));
            lookup.findStatic(provider, "setServer", MethodType.methodType(void.class, Supplier.class))
                    .invoke(supplier(currentServer));
            if (client) {
                Class<?> minecraft = load("net.minecraft.client.Minecraft", game);
                MethodHandle instance = lookup.findStatic(minecraft, "getInstance", MethodType.methodType(minecraft));
                lookup.findStatic(provider, "setClient", MethodType.methodType(void.class, Supplier.class))
                        .invoke(supplier(instance));
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Forge's work queues (LogicalSidedProvider) could not be set: " + e, e);
        }
    }

    /**
     * Answers {@link HostChannels} from NeoForge's negotiated channels ({@code NetworkRegistry.hasChannel(Connection,
     * ConnectionProtocol, Identifier)}), so that the game layer's hooks on Forge's sends drop a message for a peer
     * without its channel; a connection without NeoForge's payload setup (never negotiated) counts as having it.
     *
     * @throws IllegalStateException when NeoForge's lookups are not as expected
     */
    static void installChannels(ClassLoader game) {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            Class<?> connection = load(CONNECTION, game);
            Class<?> registry = load("net.neoforged.neoforge.network.registration.NetworkRegistry", game);
            Class<?> attributes = load("net.neoforged.neoforge.network.registration.ChannelAttributes", game);
            Class<?> setup = load("net.neoforged.neoforge.network.registration.NetworkPayloadSetup", game);
            MethodHandle hasChannel = lookup.findStatic(
                            registry,
                            "hasChannel",
                            MethodType.methodType(
                                    boolean.class,
                                    connection,
                                    load("net.minecraft.network.ConnectionProtocol", game),
                                    load(IDENTIFIER, game)))
                    .asType(MethodType.methodType(boolean.class, Object.class, Object.class, Object.class));
            MethodHandle payloadSetup = lookup.findStatic(
                            attributes, "getPayloadSetup", MethodType.methodType(setup, connection))
                    .asType(MethodType.methodType(Object.class, Object.class));
            // A packet listener (the game layer passes a player's, whose connection only NeoForge's extension gives).
            Class<?> listener = load("net.neoforged.neoforge.common.extensions.ICommonPacketListener", game);
            MethodHandle listenerConnection = lookup.findVirtual(
                            listener, "getConnection", MethodType.methodType(connection))
                    .asType(MethodType.methodType(Object.class, Object.class));
            HostChannels.install((peer, protocol, id) -> {
                try {
                    Object of = listener.isInstance(peer) ? (Object) listenerConnection.invokeExact(peer) : peer;
                    return payloadSetup.invokeExact(of) == null || (boolean) hasChannel.invokeExact(of, protocol, id);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NeoForge's negotiated channels could not be read: " + e, e);
        }
    }

    private static Supplier<Object> supplier(MethodHandle getter) {
        MethodHandle generic = getter.asType(MethodType.methodType(Object.class));
        return () -> {
            try {
                return generic.invoke();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
