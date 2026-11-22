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

    /** Where a pair is passed on. */
    public enum Where {
        /** In every game. */
        ANY,
        /**
         * In a client game only: both events name client classes in their members, which a dedicated server does not
         * have. The pre-launch check still checks the pair on every side.
         */
        CLIENT
    }

    /** NeoForge's event {@code neoForge} posted as Forge's {@code forge}, in the games {@code where} says. */
    public record Pair(String neoForge, String forge, List<Argument> arguments, Where where) {
        public Pair {
            Objects.requireNonNull(neoForge, "neoForge");
            Objects.requireNonNull(forge, "forge");
            arguments = List.copyOf(arguments);
            Objects.requireNonNull(where, "where");
        }

        /** A pair passed on in every game. */
        public Pair(String neoForge, String forge, List<Argument> arguments) {
            this(neoForge, forge, arguments, Where.ANY);
        }
    }

    private static final String NEOFORGE = "net.neoforged.neoforge.event.";
    private static final String NEOFORGE_CLIENT = "net.neoforged.neoforge.client.event.";
    private static final String FORGE = "net.minecraftforge.event.";
    private static final String FORGE_CLIENT = "net.minecraftforge.client.event.";

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
                    List.of(new Getter("getEntity"), new Getter("getLevel"), new Getter("loadedFromDisk"))),
            // The pairs below were compared on the 26.2 patched client jars, as above (2026-11-22).
            // PlayerList.placeNewPlayer: ForgeEventFactory.firePlayerLoggedIn / EventHooks.firePlayerLoggedIn.
            new Pair(
                    NEOFORGE + "entity.player.PlayerEvent$PlayerLoggedInEvent",
                    FORGE + "entity.player.PlayerEvent$PlayerLoggedInEvent",
                    List.of(new Getter("getEntity"))),
            // PlayerList.remove: firePlayerLoggedOut in both.
            new Pair(
                    NEOFORGE + "entity.player.PlayerEvent$PlayerLoggedOutEvent",
                    FORGE + "entity.player.PlayerEvent$PlayerLoggedOutEvent",
                    List.of(new Getter("getEntity"))),
            // PlayerList.respawn: firePlayerRespawnEvent in both.
            new Pair(
                    NEOFORGE + "entity.player.PlayerEvent$PlayerRespawnEvent",
                    FORGE + "entity.player.PlayerEvent$PlayerRespawnEvent",
                    List.of(new Getter("getEntity"), new Getter("isEndConquered"))),
            // ServerPlayer.teleport: ForgeEventFactory.onPlayerChangedDimension /
            // EventHooks.firePlayerChangedDimensionEvent.
            new Pair(
                    NEOFORGE + "entity.player.PlayerEvent$PlayerChangedDimensionEvent",
                    FORGE + "entity.player.PlayerEvent$PlayerChangedDimensionEvent",
                    List.of(new Getter("getEntity"), new Getter("getFrom"), new Getter("getTo"))),
            // Player.tick: ForgeEventFactory.onPlayerPreTick / EventHooks.firePlayerTickPre. A player ticks on the
            // thread of its level, so the side agrees as for the level ticks.
            new Pair(
                    NEOFORGE + "tick.PlayerTickEvent$Pre",
                    FORGE + "TickEvent$PlayerTickEvent$Pre",
                    List.of(new Getter("getEntity"), new Side())),
            // Player.tick: onPlayerPostTick / firePlayerTickPost.
            new Pair(
                    NEOFORGE + "tick.PlayerTickEvent$Post",
                    FORGE + "TickEvent$PlayerTickEvent$Post",
                    List.of(new Getter("getEntity"), new Side())),
            // Minecraft.tick: ForgeEventFactory.onPreClientTick / ClientHooks.fireClientTickPre. NeoForge posts both
            // client
            // tick events only once the game has loaded (Minecraft.gameLoadFinished), Forge on every tick: Forge mods
            // get no client ticks while the game loads.
            new Pair(
                    NEOFORGE_CLIENT + "ClientTickEvent$Pre",
                    FORGE + "TickEvent$ClientTickEvent$Pre",
                    List.of(),
                    Where.CLIENT),
            // Minecraft.tick: onPostClientTick / fireClientTickPost.
            new Pair(
                    NEOFORGE_CLIENT + "ClientTickEvent$Post",
                    FORGE + "TickEvent$ClientTickEvent$Post",
                    List.of(),
                    Where.CLIENT),
            // Minecraft.renderFrame: ForgeEventFactoryClient.onRenderTickStart / ClientHooks.fireRenderFramePre.
            new Pair(
                    NEOFORGE_CLIENT + "RenderFrameEvent$Pre",
                    FORGE + "TickEvent$RenderTickEvent$Pre",
                    List.of(new Getter("getPartialTick")),
                    Where.CLIENT),
            // Minecraft.renderFrame: onRenderTickEnd / fireRenderFramePost.
            new Pair(
                    NEOFORGE_CLIENT + "RenderFrameEvent$Post",
                    FORGE + "TickEvent$RenderTickEvent$Post",
                    List.of(new Getter("getPartialTick")),
                    Where.CLIENT),
            // The pairs below were compared on the 26.2 patched client jars, as above (2026-11-22, F-N6); their event
            // shapes are the same on the five 26.x releases.
            // DedicatedServer, IntegratedServer and GameTestServer.initServer:
            // ServerLifecycleHooks.handleServerAboutToStart
            // in both. Forge loads the server configs just before it (the host's ForgeConfigs too).
            new Pair(
                    NEOFORGE + "server.ServerAboutToStartEvent",
                    FORGE + "server.ServerAboutToStartEvent",
                    List.of(new Getter("getServer"))),
            // MinecraftServer.createLevels and ClientLevel.<init>: ForgeEventFactory.onLevelLoad / NeoForge directly.
            new Pair(
                    NEOFORGE + "level.LevelEvent$Load",
                    FORGE + "level.LevelEvent$Load",
                    List.of(new Getter("getLevel"))),
            // MinecraftServer.stopServer, Minecraft.setLevel and Minecraft.disconnect: ForgeEventFactory.onLevelUnload
            // /
            // NeoForge directly; NeoForge also posts it from Minecraft.clearClientLevel, where Forge does not.
            new Pair(
                    NEOFORGE + "level.LevelEvent$Unload",
                    FORGE + "level.LevelEvent$Unload",
                    List.of(new Getter("getLevel"))),
            // ServerPlayer.restoreFrom: ForgeEventFactory.onPlayerClone / EventHooks.onPlayerClone.
            new Pair(
                    NEOFORGE + "entity.player.PlayerEvent$Clone",
                    FORGE + "entity.player.PlayerEvent$Clone",
                    List.of(new Getter("getEntity"), new Getter("getOriginal"), new Getter("isWasDeath"))),
            // The pairs below were compared on the 26.2 patched client jars, as above, and the hooks' bodies on the
            // universal jars (2026-11-22, F-N6): both build their event from the same arguments.
            // ClientPacketListener.handleLogin: ForgeEventFactoryClient.firePlayerLogin / ClientHooks.firePlayerLogin.
            new Pair(
                    NEOFORGE_CLIENT + "ClientPlayerNetworkEvent$LoggingIn",
                    FORGE_CLIENT + "ClientPlayerNetworkEvent$LoggingIn",
                    List.of(new Getter("getMultiPlayerGameMode"), new Getter("getPlayer"), new Getter("getConnection")),
                    Where.CLIENT),
            // Minecraft.disconnect: firePlayerLogout in both; the game mode, player and connection may be null.
            new Pair(
                    NEOFORGE_CLIENT + "ClientPlayerNetworkEvent$LoggingOut",
                    FORGE_CLIENT + "ClientPlayerNetworkEvent$LoggingOut",
                    List.of(new Getter("getMultiPlayerGameMode"), new Getter("getPlayer"), new Getter("getConnection")),
                    Where.CLIENT),
            // ClientPacketListener.handleRespawn: firePlayerRespawn in both.
            new Pair(
                    NEOFORGE_CLIENT + "ClientPlayerNetworkEvent$Clone",
                    FORGE_CLIENT + "ClientPlayerNetworkEvent$Clone",
                    List.of(
                            new Getter("getMultiPlayerGameMode"),
                            new Getter("getOldPlayer"),
                            new Getter("getNewPlayer"),
                            new Getter("getConnection")),
                    Where.CLIENT));

    private ForgeEventPairs() {}
}
