package dev.plexusmodloader.game.layer.v1_20_4;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.game.version.EventPublisher;
import dev.plexusmodloader.game.version.GameLaunchTarget;
import dev.plexusmodloader.game.version.layer.GameLayerContext;
import dev.plexusmodloader.game.version.layer.LoadedModCounts;
import dev.plexusmodloader.game.version.layer.NativeDeclarations;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Builds what the layer's tests need around the game: bootstrapped registries and mod declarations. */
public final class LayerTestSupport {

    private static boolean bootstrapped;

    private LayerTestSupport() {}

    /** Bootstraps the game's registries once, as the game does before it loads anything. */
    public static synchronized void bootstrap() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    /**
     * Declarations that are empty except for the given lists, by method name (for example {@code "channels"}).
     * Methods that return nothing do nothing.
     */
    public static NativeDeclarations declarations(Map<String, Object> lists) {
        return (NativeDeclarations) Proxy.newProxyInstance(
                NativeDeclarations.class.getClassLoader(),
                new Class<?>[] {NativeDeclarations.class},
                (proxy, method, args) -> {
                    if (lists.containsKey(method.getName())) {
                        return lists.get(method.getName());
                    }
                    if (method.getReturnType() == List.class) {
                        return List.of();
                    }
                    if (method.getReturnType() == Optional.class) {
                        return Optional.empty();
                    }
                    return null;
                });
    }

    /** Events that nobody listens to. */
    public static EventPublisher noEvents() {
        return new EventPublisher() {
            @Override
            public <L> void publish(
                    dev.plexusmodloader.api.event.EventKey<L> event, java.util.function.Consumer<L> invocation) {}

            @Override
            public <L, R> R publishUntilDecided(
                    dev.plexusmodloader.api.event.EventKey<L> event,
                    java.util.function.Function<L, R> invocation,
                    R undecided) {
                return undecided;
            }

            @Override
            public boolean hasListeners(dev.plexusmodloader.api.event.EventKey<?> event) {
                return false;
            }
        };
    }

    /** Starts the layer with the given declarations on a dedicated server, as the runtime does. */
    public static void startLayer(NativeDeclarations declarations) {
        startLayer(declarations, noEvents(), GameSide.DEDICATED_SERVER);
    }

    /** Starts the layer with the given declarations, events and side. */
    public static void startLayer(NativeDeclarations declarations, EventPublisher events, GameSide side) {
        new GameLayer1_20_4()
                .start(new GameLayerContext(
                        side,
                        side == GameSide.CLIENT ? GameLaunchTarget.CLIENT : GameLaunchTarget.DEDICATED_SERVER,
                        events,
                        List.of(),
                        declarations,
                        LoadedModCounts.NONE));
    }

    /** Events with the listeners that tests add; a published event calls them in order. */
    public static final class RecordingEvents implements EventPublisher {

        private final java.util.Map<dev.plexusmodloader.api.event.EventKey<?>, List<Object>> listeners =
                new java.util.HashMap<>();

        public <L> void listen(dev.plexusmodloader.api.event.EventKey<L> event, L listener) {
            listeners.computeIfAbsent(event, key -> new java.util.ArrayList<>()).add(listener);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <L> void publish(
                dev.plexusmodloader.api.event.EventKey<L> event, java.util.function.Consumer<L> invocation) {
            for (Object listener : listeners.getOrDefault(event, List.of())) {
                invocation.accept((L) listener);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <L, R> R publishUntilDecided(
                dev.plexusmodloader.api.event.EventKey<L> event,
                java.util.function.Function<L, R> invocation,
                R undecided) {
            for (Object listener : listeners.getOrDefault(event, List.of())) {
                R result = invocation.apply((L) listener);
                if (!java.util.Objects.equals(result, undecided)) {
                    return result;
                }
            }
            return undecided;
        }

        @Override
        public boolean hasListeners(dev.plexusmodloader.api.event.EventKey<?> event) {
            return !listeners.getOrDefault(event, List.of()).isEmpty();
        }
    }
}
