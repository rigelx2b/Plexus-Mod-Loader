package dev.plexusmodloader.game.layer.v1_21_1;

import com.google.common.collect.MapMaker;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Lifecycle;
import dev.plexusmodloader.api.entity.DamageInfo;
import dev.plexusmodloader.api.entity.LivingEntityView;
import dev.plexusmodloader.api.event.EventKey;
import dev.plexusmodloader.api.event.EventResult;
import dev.plexusmodloader.api.event.block.BlockEvents;
import dev.plexusmodloader.api.event.entity.EntityEvents;
import dev.plexusmodloader.api.event.player.PlayerEvents;
import dev.plexusmodloader.api.event.world.ChunkListener;
import dev.plexusmodloader.api.event.world.WorldEvents;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.world.WorldView;
import dev.plexusmodloader.game.layer.v1_21_1.commands.CommandInstaller;
import dev.plexusmodloader.game.layer.v1_21_1.content.ContentInstaller;
import dev.plexusmodloader.game.layer.v1_21_1.content.ItemComponents;
import dev.plexusmodloader.game.layer.v1_21_1.content.ModCreativeTabs;
import dev.plexusmodloader.game.layer.v1_21_1.content.Particles;
import dev.plexusmodloader.game.layer.v1_21_1.entity.EntityInstaller;
import dev.plexusmodloader.game.layer.v1_21_1.gametest.GameTests;
import dev.plexusmodloader.game.layer.v1_21_1.worldgen.CodeWorldgen;
import dev.plexusmodloader.game.layer.v1_21_1.worldgen.PlexusChunkGenerator;
import dev.plexusmodloader.game.version.layer.GameLayerContext;
import dev.plexusmodloader.game.version.layer.NativeDeclarations;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static hooks that Minecraft 1.21.1 code calls after Plexus Mod Loader's transformation rules (see
 * {@code Minecraft1_21_1Adapter}). Each parameter list matches the rule's target exactly. Client-only
 * hooks are in {@code client.ClientHooks} so the dedicated server never loads client classes.
 */
public final class GameHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");
    private static final int BUILT_IN_DIMENSIONS = 3;

    /** The block a player is breaking on this thread, between playerWillDestroy and destroyBlock's return. */
    private static final ThreadLocal<PendingBreak> PENDING_BREAK = new ThreadLocal<>();

    private record PendingBreak(net.minecraft.core.BlockPos pos, BlockState state, Player player) {}

    private static final AtomicBoolean REGISTRIES_FREEZE_HOOK_RAN = new AtomicBoolean();

    private GameHooks() {}

    /** Whether {@link #beforeRegistriesFreeze()} has run, that is whether the game has reached its registry freeze. */
    public static boolean registriesFreezeHookRan() {
        return REGISTRIES_FREEZE_HOOK_RAN.get();
    }

    /**
     * Head of {@code BuiltInRegistries.freeze()}: the last moment content can be registered. It runs once: Fabric API's
     * registry sync takes the freeze out of {@code Bootstrap} and does it in {@code MinecraftServer.runServer}, and the
     * game test start freezes by itself before that, so the game may reach {@code freeze()} twice.
     */
    public static void beforeRegistriesFreeze() {
        if (!REGISTRIES_FREEZE_HOOK_RAN.compareAndSet(false, true)) {
            return;
        }
        LayerState.context().ifPresent(context -> {
            ItemComponents.install(context.declarations().itemComponents());
            Particles.install(context.declarations().particles());
            PlexusChunkGenerator.install(context.declarations().chunkGenerators());
            CodeWorldgen.install(
                    context.declarations().codeFeatures(),
                    context.declarations().densityFunctions(),
                    context.declarations().structures());
            ContentInstaller.install(context.declarations());
            EntityInstaller.install(context.declarations());
            GameTests.install(context.declarations().gameTests());
            GameTags.checkDeclaredEntries(context.declarations().tagEntries());
            ModCreativeTabs.install(context.declarations().creativeTabs());
            context.declarations().close();
        });
    }

    /** Head of {@code BuiltInPackSource.loadPacks(Consumer)}. */
    public static void onLoadPacks(BuiltInPackSource source, Consumer<Pack> output) {
        ModPacks.addTo(source, output);
    }

    /** Before the return of {@code Commands(CommandSelection, CommandBuildContext)}. */
    public static void onCommandsCreated(
            Commands commands, Commands.CommandSelection selection, CommandBuildContext buildContext) {
        LayerState.context()
                .ifPresent(context -> CommandInstaller.install(
                        commands, context.declarations().commands()));
    }

    /** Head of {@code CustomPacketPayload.codec(FallbackProvider, List)}. */
    public static <B extends FriendlyByteBuf> StreamCodec<B, CustomPacketPayload> onPayloadCodec(
            CustomPacketPayload.FallbackProvider<B> fallback,
            List<CustomPacketPayload.TypeAndCodec<? super B, ?>> types) {
        return Networking.extendCodec(fallback, types);
    }

    /** Head of {@code ServerGamePacketListenerImpl.handleCustomPayload}; {@code true} skips the game's handler. */
    public static boolean onServerPayload(
            ServerGamePacketListenerImpl listener, ServerboundCustomPayloadPacket packet) {
        return Networking.receiveOnServer(listener, packet);
    }

    /** Head of {@code ServerConfigurationPacketListenerImpl.addOptionalTasks}: mods' configuration exchanges. */
    public static void onAddConfigurationTasks(ServerConfigurationPacketListenerImpl listener) {
        ConfigurationExchanges.addTasks(listener);
    }

    /**
     * Head of {@code GameTestServer.tickServer(BooleanSupplier)}: runs the game test ticker. 1.21.1's {@code
     * MinecraftServer.tickServer} only does so when {@code SharedConstants.IS_RUNNING_IN_IDE} is set, which a game test
     * server started by Plexus Mod Loader is not; setting that flag would change other things (a missing data fixer
     * becomes an exception). The ticker runs the tests once per tick, as the game does in an IDE.
     */
    public static void onGameTestTick(
            net.minecraft.gametest.framework.GameTestServer server, BooleanSupplier hasTimeLeft) {
        if (server.tickRateManager().runsNormally()) {
            net.minecraft.gametest.framework.GameTestTicker.SINGLETON.tick();
        }
    }

    /** Head of {@code ServerConfigurationPacketListenerImpl.tick()}: polls the connection's waiting exchange. */
    public static void onConfigurationTick(ServerConfigurationPacketListenerImpl listener) {
        ConfigurationExchanges.tick(listener);
    }

    /**
     * Head of {@code ServerCommonPacketListenerImpl.handleCustomPayload}, the configuration phase's
     * handler; {@code true} when Plexus Mod Loader consumed an answer to a configuration exchange.
     */
    public static boolean onCommonPayload(
            ServerCommonPacketListenerImpl listener, ServerboundCustomPayloadPacket packet) {
        return ConfigurationExchanges.receive(listener, packet);
    }

    /** Before the return of {@code PlayerList.placeNewPlayer}. */
    public static void onPlayerJoined(
            PlayerList players, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        EntityData.sendAll(player);
        WorldServices.sendSynced(player);
        events().ifPresent(context -> context.events()
                .publish(PlayerEvents.JOINED, listener -> listener.onPlayerJoined(Views.player(player))));
    }

    /** Head of {@code PlayerList.remove(ServerPlayer)}. */
    public static void onPlayerLeaving(PlayerList players, ServerPlayer player) {
        events().ifPresent(context -> context.events()
                .publish(PlayerEvents.LEAVING, listener -> listener.onPlayerLeaving(Views.player(player))));
    }

    /** Head of {@code ServerPlayer.tick()}: fires {@code PlayerEvents.TICK} for every server player. */
    public static void onPlayerTick(ServerPlayer player) {
        GameLayerContext context = LayerState.context().orElse(null);
        if (context != null && context.events().hasListeners(PlayerEvents.TICK)) {
            context.events().publish(PlayerEvents.TICK, listener -> listener.onPlayerTick(Views.player(player)));
        }
    }

    /**
     * Before each return of {@code AbstractFurnaceBlockEntity.getFuel()}: the game's fuel table with the mod items'
     * burn times added (the table is built once and cached, so adding is repeated harmlessly).
     */
    public static java.util.Map<net.minecraft.world.item.Item, Integer> onFuelTable(
            java.util.Map<net.minecraft.world.item.Item, Integer> fuel) {
        fuel.putAll(dev.plexusmodloader.game.layer.v1_21_1.content.ModFuel.burnTicks());
        return fuel;
    }

    /** Head of {@code Block.playerWillDestroy}: remembers what the player is breaking. */
    public static void onPlayerWillDestroy(
            Block block, Level level, net.minecraft.core.BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            PENDING_BREAK.set(new PendingBreak(pos.immutable(), state, player));
        }
    }

    /** Before the return of {@code ServerPlayerGameMode.destroyBlock(BlockPos)}. */
    public static boolean onDestroyBlockReturn(
            ServerPlayerGameMode gameMode, net.minecraft.core.BlockPos pos, boolean removed) {
        PendingBreak pending = PENDING_BREAK.get();
        PENDING_BREAK.remove();
        if (removed && pending != null && pending.pos().equals(pos)) {
            events().ifPresent(context -> context.events()
                    .publish(
                            BlockEvents.BROKEN,
                            listener -> listener.onBlockBroken(
                                    Views.world(pending.player().level()),
                                    Views.pos(pos),
                                    Views.blockId(pending.state().getBlock()),
                                    Views.player(pending.player()))));
        }
        return removed;
    }

    /** Head of {@code LevelChunk.registerAllBlockEntitiesAfterLevelLoad()}: a chunk finished loading. */
    public static void onChunkLoaded(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel level) {
            publishChunk(WorldEvents.CHUNK_LOADED, level, chunk);
        }
    }

    /** Head of {@code ServerLevel.unload(LevelChunk)}. */
    public static void onChunkUnloaded(ServerLevel level, LevelChunk chunk) {
        publishChunk(WorldEvents.CHUNK_UNLOADED, level, chunk);
    }

    private static void publishChunk(EventKey<ChunkListener> key, ServerLevel level, LevelChunk chunk) {
        LayerState.context().ifPresent(context -> {
            if (context.events().hasListeners(key)) {
                WorldView world = Views.world(level);
                int chunkX = chunk.getPos().x;
                int chunkZ = chunk.getPos().z;
                context.events().publish(key, listener -> listener.onChunk(world, chunkX, chunkZ));
            }
        });
    }

    /** Head of {@code ServerLevel.tick(BooleanSupplier)}: runs scheduled tasks of that dimension. */
    public static void onLevelTick(ServerLevel level, BooleanSupplier hasTimeLeft) {
        WorldServices.tick(level);
    }

    /** Before each return of {@code ServerLevel.tick(BooleanSupplier)}. */
    public static void onLevelTickEnd(ServerLevel level, BooleanSupplier hasTimeLeft) {
        WorldServices.tickEnd(level);
    }

    /**
     * Head of {@code CreativeModeTabs.validate()}: {@code true} skips the game's position check when
     * mod tabs on extra pages share positions with the game's tabs (Plexus Mod Loader checks per page).
     */
    public static boolean onValidateCreativeTabs() {
        return ModCreativeTabs.validate();
    }

    /** Before the return of {@code CreativeModeTab.buildContents}. */
    public static void onCreativeTabBuilt(CreativeModeTab tab, CreativeModeTab.ItemDisplayParameters parameters) {
        ContentInstaller.appendToCreativeTab(tab);
        ModCreativeTabs.appendEntries(tab);
    }

    private static java.util.Optional<GameLayerContext> events() {
        return LayerState.context();
    }

    static Logger logger() {
        return LOGGER;
    }

    /** Head of {@code DefaultAttributes.getSupplier}: attributes of mod mobs, null for the game's. */
    public static AttributeSupplier onGetAttributes(EntityType<? extends LivingEntity> type) {
        return EntityInstaller.attributes(type);
    }

    /** Head of {@code DefaultAttributes.hasSupplier}: TRUE for mod mobs, null for the game's. */
    public static Boolean onHasAttributes(EntityType<?> type) {
        return EntityInstaller.hasAttributes(type);
    }

    /** The entity whose damage Plexus Mod Loader is re-dealing with a modified amount on this thread. */
    private static final ThreadLocal<LivingEntity> REDEALING = new ThreadLocal<>();

    /**
     * Head of {@code LivingEntity.hurt(DamageSource, float)}: FALSE when a listener cancels the damage; the result
     * of dealing the modified amount instead when a listener changes it; else null (the game goes on). Damage is
     * only dealt on the server in 1.21.1; the client's call goes on untouched.
     */
    public static Boolean onHurt(LivingEntity entity, DamageSource source, float amount) {
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null || REDEALING.get() == entity || entity.level().isClientSide()) {
            return null;
        }
        if (context.events().hasListeners(EntityEvents.AFTER_DAMAGE)
                && !entity.level().isClientSide()) {
            DAMAGE_CONTEXT.set(new DamageContext(
                    entity, source, amount, entity.getHealth(), entity.level().getGameTime()));
        }
        boolean allowListeners = context.events().hasListeners(EntityEvents.ALLOW_DAMAGE);
        boolean modifyListeners = context.events().hasListeners(EntityEvents.MODIFY_DAMAGE);
        if (!allowListeners && !modifyListeners) {
            return null;
        }
        LivingEntityView view = (LivingEntityView) Views.entity(entity);
        if (allowListeners) {
            DamageInfo damage = damage(source, amount);
            AtomicBoolean allowed = new AtomicBoolean(true);
            context.events().publish(EntityEvents.ALLOW_DAMAGE, listener -> {
                if (!listener.allowDamage(view, damage)) {
                    allowed.set(false);
                }
            });
            if (!allowed.get()) {
                return Boolean.FALSE;
            }
        }
        if (!modifyListeners) {
            return null;
        }
        float[] modified = {amount};
        context.events()
                .publish(
                        EntityEvents.MODIFY_DAMAGE,
                        listener -> modified[0] = listener.modifyDamage(view, damage(source, modified[0])));
        if (modified[0] == amount) {
            return null;
        }
        if (!(modified[0] > 0)) {
            return Boolean.FALSE;
        }
        LivingEntity previous = REDEALING.get();
        REDEALING.set(entity);
        try {
            return entity.hurt(source, modified[0]);
        } finally {
            REDEALING.set(previous);
        }
    }

    /** Head of {@code ServerPlayer.die(DamageSource)}, which does not call {@code LivingEntity.die}. */
    public static void onPlayerDie(ServerPlayer player, DamageSource source) {
        onDie(player, source);
    }

    /**
     * Head of {@code LivingEntity.checkTotemDeathProtection}, which the game asks after lethal damage and before
     * the entity dies: TRUE (protected) when an {@code ALLOW_DEATH} listener refuses the death, after the health is
     * set to 1; null lets the game go on (totem check, then death).
     */
    public static Boolean onCheckDeathProtection(LivingEntity entity, DamageSource source) {
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null
                || entity.level().isClientSide()
                || !context.events().hasListeners(EntityEvents.ALLOW_DEATH)) {
            return null;
        }
        LivingEntityView view = (LivingEntityView) Views.entity(entity);
        DamageInfo damage = damage(source, entity.getMaxHealth());
        AtomicBoolean allowed = new AtomicBoolean(true);
        context.events().publish(EntityEvents.ALLOW_DEATH, listener -> {
            if (!listener.allowDeath(view, damage)) {
                allowed.set(false);
            }
        });
        if (allowed.get()) {
            return null;
        }
        entity.setHealth(1.0F);
        return Boolean.TRUE;
    }

    /** The damage an entity is taking on this thread, from the head of the hurt hook to the end of the damage step. */
    private record DamageContext(
            LivingEntity entity, DamageSource source, float amount, float healthBefore, long gameTime) {}

    private static final ThreadLocal<DamageContext> DAMAGE_CONTEXT = new ThreadLocal<>();

    /**
     * After the call to {@code gameEvent} that ends {@code LivingEntity.actuallyHurt} and {@code Player.actuallyHurt}:
     * fires {@code AFTER_DAMAGE} with the health the entity lost since the hurt hook ran.
     */
    public static void onDamageApplied(
            LivingEntity entity, net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event) {
        DamageContext damage = DAMAGE_CONTEXT.get();
        if (damage == null
                || damage.entity() != entity
                || damage.gameTime() != entity.level().getGameTime()) {
            return;
        }
        DAMAGE_CONTEXT.remove();
        float lost = damage.healthBefore() - entity.getHealth();
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null || !(lost > 0)) {
            return;
        }
        LivingEntityView view = (LivingEntityView) Views.entity(entity);
        DamageInfo info = damage(damage.source(), damage.amount());
        context.events().publish(EntityEvents.AFTER_DAMAGE, listener -> listener.afterDamage(view, info, lost));
    }

    /** As {@link #onDamageApplied}, for the call in {@code Player.actuallyHurt}, whose receiver type is {@code Player}. */
    public static void onPlayerDamageApplied(
            net.minecraft.world.entity.player.Player player,
            net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event) {
        onDamageApplied(player, event);
    }

    /**
     * Head of {@code LivingEntity.dropAllDeathLoot}: {@code true} (the game drops no loot and no equipment) when a
     * {@code DROPS} listener denies it.
     */
    public static boolean onDropAllDeathLoot(LivingEntity entity, ServerLevel level, DamageSource source) {
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null
                || entity.level().isClientSide()
                || !context.events().hasListeners(EntityEvents.DROPS)) {
            return false;
        }
        LivingEntityView view = (LivingEntityView) Views.entity(entity);
        DamageInfo info = damage(source, entity.getMaxHealth());
        return context.events()
                        .publishUntilDecided(
                                EntityEvents.DROPS, listener -> listener.onDrops(view, info), EventResult.PASS)
                == EventResult.DENY;
    }

    /**
     * Head of {@code LootTable.getRandomItems(LootContext, Consumer)}: {@code true} when the roll was done here so that
     * {@code LootEvents.MODIFY} listeners could edit the items; the game then skips its own roll.
     */
    public static boolean onLootRoll(
            net.minecraft.world.level.storage.loot.LootTable table,
            net.minecraft.world.level.storage.loot.LootContext context,
            java.util.function.Consumer<net.minecraft.world.item.ItemStack> sink) {
        return LootRolls.roll(table, context, sink);
    }

    /** Head of {@code LivingEntity.die(DamageSource)}: fires DIED once per entity. */
    public static void onDie(LivingEntity entity, DamageSource source) {
        if (entity.level().isClientSide() || entity.isRemoved()) {
            return;
        }
        synchronized (DIED) {
            if (!DIED.add(entity)) {
                return;
            }
        }
        LayerState.context()
                .ifPresent(context -> context.events()
                        .publish(
                                EntityEvents.DIED,
                                listener -> listener.onDied(
                                        (LivingEntityView) Views.entity(entity),
                                        damage(source, entity.getMaxHealth()))));
        Entity cause = source.getEntity();
        if (cause != null && cause != entity) {
            LayerState.context()
                    .ifPresent(context -> context.events()
                            .publish(
                                    EntityEvents.KILLED_OTHER,
                                    listener -> listener.onKilledOther(
                                            Views.entity(cause),
                                            (LivingEntityView) Views.entity(entity),
                                            damage(source, entity.getMaxHealth()))));
        }
    }

    private static DamageInfo damage(DamageSource source, float amount) {
        ResourceId type = source.typeHolder()
                .unwrapKey()
                .map(key -> Views.id(key.location()))
                .orElse(ResourceId.minecraft("generic"));
        return new DamageInfo(
                type, amount, Optional.ofNullable(source.getEntity()).map(Views::entity));
    }

    /**
     * Living entities that already fired DIED; weak so dead entities are collected, and by identity
     * because a respawned player object takes over the dead one's entity ID (Entity.equals).
     */
    private static final Set<LivingEntity> DIED =
            Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());

    /** Before the return of {@code ReloadableServerResources.listeners()}: adds the mod data listener. */
    public static List<PreparableReloadListener> onServerReloadListeners(
            ReloadableServerResources resources, List<PreparableReloadListener> listeners) {
        List<NativeDeclarations.ReloadListenerDeclaration> data = LayerState.context()
                .map(context -> context.declarations().reloadListeners().stream()
                        .filter(declaration -> !declaration.assets())
                        .toList())
                .orElse(List.of());
        if (data.isEmpty()) {
            return listeners;
        }
        List<PreparableReloadListener> all = new ArrayList<>(listeners);
        all.add(new ModReloadListener(data));
        return List.copyOf(all);
    }

    /** Before the return of {@code Entity.saveWithoutId(CompoundTag)}; returns the tag the game goes on with. */
    public static CompoundTag onEntitySaved(Entity entity, CompoundTag tag, CompoundTag returned) {
        EntityData.save(entity, returned);
        return returned;
    }

    /** Before the return of {@code Entity.load(CompoundTag)}. */
    public static void onEntityLoaded(Entity entity, CompoundTag tag) {
        EntityData.load(entity, tag);
    }

    private static final java.lang.reflect.Field SERVER_ENTITY;

    static {
        try {
            SERVER_ENTITY = ServerEntity.class.getDeclaredField("entity");
            SERVER_ENTITY.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Before the return of {@code ServerEntity.addPairing}: a player starts seeing an entity. */
    public static void onStartTracking(ServerEntity tracked, ServerPlayer player) {
        try {
            EntityData.sendToNewTracker(player, (Entity) SERVER_ENTITY.get(tracked));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Head of {@code PlayerList.canPlayerLogin}: a refusal message from a mod, else null. */
    public static Component onCanPlayerLogin(PlayerList players, SocketAddress address, GameProfile player) {
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null || !context.events().hasListeners(PlayerEvents.LOGIN_CHECK)) {
            return null;
        }
        String[] refusal = {null};
        context.events().publish(PlayerEvents.LOGIN_CHECK, listener -> {
            if (refusal[0] == null) {
                listener.checkLogin(player.getId(), player.getName()).ifPresent(reason -> refusal[0] = reason);
            }
        });
        return refusal[0] == null ? null : Component.literal(refusal[0]);
    }

    /**
     * Before the return of {@code ServerPlayer.restoreFrom}: the respawned player keeps its data, then
     * {@code PlayerEvents.COPY} fires.
     */
    public static void onPlayerRestored(ServerPlayer player, ServerPlayer oldPlayer, boolean restoreAll) {
        EntityData.copy(oldPlayer, player);
        LifecycleHooks.onPlayerCopied(player, oldPlayer, restoreAll);
    }

    /**
     * Head of {@code MinecraftServer.createLevels(ChunkProgressListener)}: attaches mod features to biomes, then fires
     * {@code ServerEvents.STARTING}.
     */
    public static void onCreateLevels(
            MinecraftServer server, net.minecraft.server.level.progress.ChunkProgressListener listener) {
        WorldBackups.beforeLoading(server);
        LayerState.registries(server.registryAccess());
        LayerState.context().ifPresent(context -> {
            WorldgenModifier.apply(
                    server.registryAccess(),
                    context.declarations().features(),
                    context.declarations().featureRemovals());
            WorldgenModifier.applySpawns(
                    server.registryAccess(),
                    WorldgenModifier.withDeclared(
                            EntityInstaller.naturalSpawns(),
                            context.declarations().spawns()),
                    context.declarations().spawnRemovals());
            TradeInstaller.apply(
                    context.declarations().villagerTrades(),
                    context.declarations().wanderingTrades(),
                    context.declarations().villagerTradeRemovals(),
                    context.declarations().wanderingTradeRemovals());
        });
        LifecycleHooks.onServerStarting(server);
    }

    /**
     * Head of {@code WorldDimensions.checkStability}: dimensions in the namespace of a loaded Plexus Mod Loader
     * mod are installed content, not experimental data packs, so they count as stable; others keep
     * the game's verdict (null).
     */
    public static Lifecycle onCheckDimensionStability(ResourceKey<LevelStem> key, LevelStem stem) {
        String namespace = key.location().getNamespace();
        boolean modDimension = LayerState.context()
                .map(context -> context.resourcePacks().stream()
                        .anyMatch(pack -> pack.modId().equals(namespace)))
                .orElse(false);
        return modDimension ? Lifecycle.stable() : null;
    }

    /**
     * Stands in for {@code entries.size()} in {@code WorldDimensions.bake}, which calls a world stable only when it has
     * exactly the three built-in dimensions; a mod's dimension would make every world ask for a backup when it opens.
     * Returns the game's count of built-in dimensions when all of them are there, as 26.x asks, and a value that cannot
     * match when one is missing. Each dimension's own stability still counts ({@link #onCheckDimensionStability}).
     */
    public static int onBakedDimensionCount(List<?> entries) {
        Set<Object> missing = new HashSet<>(Set.of(LevelStem.OVERWORLD, LevelStem.NETHER, LevelStem.END));
        try {
            for (Object entry : entries) {
                Method key = entry.getClass().getDeclaredMethod("key");
                key.setAccessible(true);
                missing.remove(key.invoke(entry));
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.error(
                    "[Plexus Mod Loader] PLEXUS-WORLDGEN-006 cannot read a world's dimensions; a world with a mod's"
                            + " dimension asks for a backup when it opens",
                    e);
            return entries.size();
        }
        return missing.isEmpty() ? BUILT_IN_DIMENSIONS : -1;
    }
}
