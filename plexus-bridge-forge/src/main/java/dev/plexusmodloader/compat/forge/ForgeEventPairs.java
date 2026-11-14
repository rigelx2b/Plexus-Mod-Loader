package dev.plexusmodloader.compat.forge;

import java.util.List;
import java.util.Objects;

/**
 * Forge on NeoForge, F-N3 ({@code docs/plans/FORGE_ON_NEOFORGE.md}, ADR-0051): the game events NeoForge posts that Forge
 * mods get as Forge's own event, as data. NeoForge's patches fire their event where Forge's patches fire Forge's; for
 * every pair the two patched games of Minecraft 26.2 were compared with {@code javap} (Forge 26.2-65.1.5 client jar and
 * universal jar, NeoForge 26.2.0.93 patched client jar and universal jar): both events are constructed in, or by a hook
 * called from, the same game methods, named in each pair's comment.
 *
 * <p>Each Forge event is built with its public constructor from {@link Pair#arguments()} in order and posted to its own
 * {@code BUS}; when Forge's bus is cancellable and a listener cancels, NeoForge's event is cancelled too. Names are
 * binary class names ({@code Outer$Inner}) and method names, so that this table names no Minecraft class.
 *
 * <p>Thread: immutable.
 */
public final class ForgeEventPairs {

    /** One constructor argument of the Forge event. */
    public sealed interface Argument permits Getter, TimeLeft, Side {}

    /** The value of the NeoForge event's public no-argument method {@code method}, of the same type. */
    public record Getter(String method) implements Argument {
        public Getter {
            Objects.requireNonNull(method, "method");
        }
    }

    /**
     * A {@code BooleanSupplier} over the NeoForge event's {@code boolean} method {@code method}: Forge's tick events take
     * the supplier the game passes, NeoForge's ask it through that method.
     */
    public record TimeLeft(String method) implements Argument {
        public TimeLeft {
            Objects.requireNonNull(method, "method");
        }
    }

    /**
     * Forge's {@code LogicalSide} of the thread NeoForge posts on (NeoForge's {@code EffectiveSide}). Forge takes it from
     * {@code Level.isClientSide()}; the client level ticks on the client thread and the server levels on the server
     * thread, so the two agree.
     */
    public record Side() implements Argument {}

    /** NeoForge's event {@code neoForge} posted as Forge's {@code forge}. */
    public record Pair(String neoForge, String forge, List<Argument> arguments) {
        public Pair {
            Objects.requireNonNull(neoForge, "neoForge");
            Objects.requireNonNull(forge, "forge");
            arguments = List.copyOf(arguments);
        }
    }

    private static final String NEOFORGE = "net.neoforged.neoforge.event.";
    private static final String FORGE = "net.minecraftforge.event.";

    /** The game bus events passed on. */
    public static final List<Pair> GAME_EVENTS = List.of(
            // MinecraftServer.tickServer: ForgeEventFactory.onPreServerTick / EventHooks.fireServerTickPre.
            new Pair(
                    NEOFORGE + "tick.ServerTickEvent$Pre",
                    FORGE + "TickEvent$ServerTickEvent$Pre",
                    List.of(new TimeLeft("hasTime"), new Getter("getServer"))),
            // MinecraftServer.tickServer: onPostServerTick / fireServerTickPost.
            new Pair(
                    NEOFORGE + "tick.ServerTickEvent$Post",
                    FORGE + "TickEvent$ServerTickEvent$Post",
                    List.of(new TimeLeft("hasTime"), new Getter("getServer"))),
            // MinecraftServer.tickChildren and Minecraft.tick: onPreLevelTick / fireLevelTickPre.
            new Pair(
                    NEOFORGE + "tick.LevelTickEvent$Pre",
                    FORGE + "TickEvent$LevelTickEvent$Pre",
                    List.of(new Side(), new Getter("getLevel"), new TimeLeft("hasTime"))),
            // MinecraftServer.tickChildren and Minecraft.tick: onPostLevelTick / fireLevelTickPost.
            new Pair(
                    NEOFORGE + "tick.LevelTickEvent$Post",
                    FORGE + "TickEvent$LevelTickEvent$Post",
                    List.of(new Side(), new Getter("getLevel"), new TimeLeft("hasTime"))),
            // Commands.<init>: ForgeEventFactory.onCommandRegister / EventHooks.onCommandRegister.
            new Pair(
                    NEOFORGE + "RegisterCommandsEvent",
                    FORGE + "RegisterCommandsEvent",
                    List.of(
                            new Getter("getDispatcher"),
                            new Getter("getCommandSelection"),
                            new Getter("getBuildContext"))),
            // DedicatedServer.initServer and IntegratedServer.initServer: ServerLifecycleHooks.handleServerStarting in
            // both (NeoForge's GameTestServer.initServer too).
            new Pair(
                    NEOFORGE + "server.ServerStartingEvent",
                    FORGE + "server.ServerStartingEvent",
                    List.of(new Getter("getServer"))),
            // MinecraftServer.runServer: ServerLifecycleHooks.handleServerStarted in both.
            new Pair(
                    NEOFORGE + "server.ServerStartedEvent",
                    FORGE + "server.ServerStartedEvent",
                    List.of(new Getter("getServer"))),
            // MinecraftServer.runServer: ServerLifecycleHooks.handleServerStopping in both.
            new Pair(
                    NEOFORGE + "server.ServerStoppingEvent",
                    FORGE + "server.ServerStoppingEvent",
                    List.of(new Getter("getServer"))),
            // MinecraftServer.runServer: ServerLifecycleHooks.handleServerStopped in both.
            new Pair(
                    NEOFORGE + "server.ServerStoppedEvent",
                    FORGE + "server.ServerStoppedEvent",
                    List.of(new Getter("getServer"))),
            // PersistentEntitySectionManager.addEntity, ServerLevel.addPlayer, ClientLevel.addEntity: Forge through
            // ForgeEventFactory.onEntityJoinLevel, NeoForge directly; a cancelled event keeps the entity out in both.
            new Pair(
                    NEOFORGE + "entity.EntityJoinLevelEvent",
                    FORGE + "entity.EntityJoinLevelEvent",
                    List.of(new Getter("getEntity"), new Getter("getLevel"), new Getter("loadedFromDisk"))));

    private ForgeEventPairs() {}
}
