package dev.plexusmodloader.game.version.v1_20_4;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.api.system.SystemLookup;
import dev.plexusmodloader.game.version.EventPublisher;
import dev.plexusmodloader.game.version.GameLaunchTarget;
import dev.plexusmodloader.game.version.GameVersionAdapter;
import dev.plexusmodloader.game.version.ModInitializationCall;
import dev.plexusmodloader.internal.transform.HeadHookResult;
import dev.plexusmodloader.internal.transform.InjectStaticCallAtHead;
import dev.plexusmodloader.internal.transform.InvokeHookAtCall;
import dev.plexusmodloader.internal.transform.InvokeHookAtHead;
import dev.plexusmodloader.internal.transform.InvokeHookBeforeReturn;
import dev.plexusmodloader.internal.transform.MethodTarget;
import dev.plexusmodloader.internal.transform.MethodTransformAction;
import dev.plexusmodloader.internal.transform.RedirectCall;
import dev.plexusmodloader.internal.transform.ReplaceConstantReturnWithStaticCall;
import dev.plexusmodloader.internal.transform.TransformFailurePolicy;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Adapter for Minecraft: Java Edition 1.20.4, a <b>supported</b> version: obfuscated, so it runs on
 * the game jar remapped to Mojang names with Mojang's mappings, on Java 25. The 1.20.4 server jar has no
 * {@code net.minecraft.gametest.Main}, so the game test server starts at the game layer's own {@code GameTestMain}.
 */
public final class Minecraft1_20_4Adapter implements GameVersionAdapter {

    private volatile boolean client;

    static final String GAME_VERSION = "1.20.4";
    private static final String HOOKS = "dev/plexusmodloader/game/version/v1_20_4/Minecraft1_20_4Hooks";
    private static final String GAME_HOOKS = "dev/plexusmodloader/game/layer/v1_20_4/GameHooks";
    private static final String CLIENT_HOOKS = "dev/plexusmodloader/game/layer/v1_20_4/client/ClientHooks";
    private static final String INTERACTION_HOOKS = "dev/plexusmodloader/game/layer/v1_20_4/InteractionHooks";
    private static final String LIFECYCLE_HOOKS = "dev/plexusmodloader/game/layer/v1_20_4/LifecycleHooks";
    private static final String CHAT_HOOKS = "dev/plexusmodloader/game/layer/v1_20_4/ChatHooks";
    private static final String EQUIPMENT_HOOKS = "dev/plexusmodloader/game/layer/v1_20_4/EquipmentHooks";
    private static final String SLEEP_GLIDE_HOOKS = "dev/plexusmodloader/game/layer/v1_20_4/SleepAndGlideHooks";
    private static final String CREATIVE_PAGING = "dev/plexusmodloader/game/layer/v1_20_4/client/CreativeTabPaging";
    private static final String CREATIVE_SCREEN =
            "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";
    private static final Set<GameSide> ALL_SIDES = EnumSet.allOf(GameSide.class);
    private static final Set<GameSide> CLIENT_ONLY = EnumSet.of(GameSide.CLIENT);

    @Override
    public String adapterId() {
        return "minecraft-" + GAME_VERSION;
    }

    @Override
    public String gameVersion() {
        return GAME_VERSION;
    }

    @Override
    public Set<String> gameVersions() {
        return Set.of(GAME_VERSION);
    }

    @Override
    public String mainClass(GameLaunchTarget target) {
        return switch (target) {
            case CLIENT -> "net.minecraft.client.main.Main";
            case DEDICATED_SERVER -> "net.minecraft.server.Main";
            case GAME_TEST_SERVER -> "dev.plexusmodloader.game.layer.v1_20_4.gametest.GameTestMain";
        };
    }

    @Override
    public void bindSystems(SystemLookup systems) {
        Minecraft1_20_4Hooks.bind(systems);
    }

    @Override
    public void bindEvents(EventPublisher events) {
        Minecraft1_20_4Hooks.bindEvents(events);
    }

    @Override
    public boolean bindModInitialization(GameLaunchTarget target, Runnable initialization) {
        Minecraft1_20_4Hooks.bindModInitialization(initialization);
        client = target == GameLaunchTarget.CLIENT;
        return true;
    }

    /** The calls of {@link #modInitializationRules()}, in the client's constructor and the dedicated server's main. */
    @Override
    public List<ModInitializationCall> modInitializationCalls() {
        String call = "L" + HOOKS + ";onModInitialization()V";
        return List.of(
                new ModInitializationCall(GameSide.CLIENT, "net/minecraft/client/Minecraft", "<init>", call),
                new ModInitializationCall(GameSide.DEDICATED_SERVER, "net/minecraft/server/Main", "main", call));
    }

    @Override
    public Optional<Object> gameInstance() {
        if (!client) {
            return Optional.ofNullable(Minecraft1_20_4Hooks.runningServer());
        }
        try {
            Class<?> minecraft = Class.forName(
                    "net.minecraft.client.Minecraft",
                    false,
                    Thread.currentThread().getContextClassLoader());
            return Optional.ofNullable(minecraft.getMethod("getInstance").invoke(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Minecraft.getInstance() is not available in this game", e);
        }
    }

    @Override
    public Optional<String> gameLayerClass() {
        return Optional.of("dev.plexusmodloader.game.layer.v1_20_4.GameLayer1_20_4");
    }

    @Override
    public List<TransformRuleDefinition> builtInTransformRules() {
        List<TransformRuleDefinition> rules = new ArrayList<>(brandAndServerRules());
        rules.addAll(nativeApiRules());
        rules.addAll(modInitializationRules());
        rules.add(titleScreenModCountsRule());
        return List.copyOf(rules);
    }

    private static TransformRuleDefinition titleScreenModCountsRule() {
        return new TransformRuleDefinition(
                "plexus:client_title_mod_counts",
                "plexus",
                GAME_VERSION,
                CLIENT_ONLY,
                new MethodTarget(
                        "net/minecraft/client/gui/screens/TitleScreen",
                        "render",
                        "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"),
                new RedirectCall(
                        new MethodTarget(
                                "net/minecraft/client/gui/GuiGraphics",
                                "drawString",
                                "(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"),
                        0,
                        "dev/plexusmodloader/game/layer/v1_20_4/client/TitleScreenModCounts",
                        "onVersionText"),
                TransformFailurePolicy.DISABLE_RULE);
    }

    private static List<TransformRuleDefinition> nativeApiRules() {
        return List.of(
                rule(
                        "plexus:content_registration",
                        ALL_SIDES,
                        "net/minecraft/core/registries/BuiltInRegistries",
                        "freeze",
                        "()V",
                        new InvokeHookAtHead(GAME_HOOKS, "beforeRegistriesFreeze", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:mod_resource_packs",
                        ALL_SIDES,
                        "net/minecraft/server/packs/repository/BuiltInPackSource",
                        "loadPacks",
                        "(Ljava/util/function/Consumer;)V",
                        new InvokeHookAtHead(GAME_HOOKS, "onLoadPacks", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:commands",
                        ALL_SIDES,
                        "net/minecraft/commands/Commands",
                        "<init>",
                        "(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V",
                        new InvokeHookBeforeReturn(GAME_HOOKS, "onCommandsCreated")),
                // Both packets decode a custom payload in a private static readPayload(ResourceLocation,
                // FriendlyByteBuf)
                // that knows only the game's own payloads (KNOWN_TYPES) and discards the rest (javap -c, 1.20.4).
                rule(
                        "plexus:payload_read_serverbound",
                        ALL_SIDES,
                        "net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket",
                        "readPayload",
                        "(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/FriendlyByteBuf;)"
                                + "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;",
                        new InvokeHookAtHead(GAME_HOOKS, "onReadPayload", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:payload_read_clientbound",
                        ALL_SIDES,
                        "net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket",
                        "readPayload",
                        "(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/FriendlyByteBuf;)"
                                + "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;",
                        new InvokeHookAtHead(GAME_HOOKS, "onReadPayload", HeadHookResult.RETURN_IF_NON_NULL)),
                // 1.20.4's ConfigurationTask has no tick of its own, so the configuration listener's tick polls the
                // waiting exchange; startConfiguration calls addOptionalTasks before it adds the join-world task.
                rule(
                        "plexus:configuration_tasks",
                        ALL_SIDES,
                        "net/minecraft/server/network/ServerConfigurationPacketListenerImpl",
                        "addOptionalTasks",
                        "()V",
                        new InvokeHookAtHead(GAME_HOOKS, "onAddConfigurationTasks", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:configuration_tick",
                        ALL_SIDES,
                        "net/minecraft/server/network/ServerConfigurationPacketListenerImpl",
                        "tick",
                        "()V",
                        new InvokeHookAtHead(GAME_HOOKS, "onConfigurationTick", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:game_test_tick",
                        ALL_SIDES,
                        "net/minecraft/gametest/framework/GameTestServer",
                        "tickServer",
                        "(Ljava/util/function/BooleanSupplier;)V",
                        new InvokeHookAtHead(GAME_HOOKS, "onGameTestTick", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:server_payloads",
                        ALL_SIDES,
                        "net/minecraft/server/network/ServerCommonPacketListenerImpl",
                        "handleCustomPayload",
                        "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V",
                        new InvokeHookAtHead(GAME_HOOKS, "onServerPayload", HeadHookResult.CANCEL_IF_TRUE)),
                rule(
                        "plexus:player_joined",
                        ALL_SIDES,
                        "net/minecraft/server/players/PlayerList",
                        "placeNewPlayer",
                        "(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;"
                                + "Lnet/minecraft/server/network/CommonListenerCookie;)V",
                        new InvokeHookBeforeReturn(GAME_HOOKS, "onPlayerJoined")),
                rule(
                        "plexus:player_leaving",
                        ALL_SIDES,
                        "net/minecraft/server/players/PlayerList",
                        "remove",
                        "(Lnet/minecraft/server/level/ServerPlayer;)V",
                        new InvokeHookAtHead(GAME_HOOKS, "onPlayerLeaving", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:block_break_capture",
                        ALL_SIDES,
                        "net/minecraft/world/level/block/Block",
                        "playerWillDestroy",
                        "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                                + "Lnet/minecraft/world/level/block/state/BlockState;"
                                + "Lnet/minecraft/world/entity/player/Player;)"
                                + "Lnet/minecraft/world/level/block/state/BlockState;",
                        new InvokeHookAtHead(GAME_HOOKS, "onPlayerWillDestroy", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:block_break",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerPlayerGameMode",
                        "destroyBlock",
                        "(Lnet/minecraft/core/BlockPos;)Z",
                        new InvokeHookAtHead(INTERACTION_HOOKS, "onDestroyBlock", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:block_broken",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerPlayerGameMode",
                        "destroyBlock",
                        "(Lnet/minecraft/core/BlockPos;)Z",
                        new InvokeHookBeforeReturn(GAME_HOOKS, "onDestroyBlockReturn"),
                        "plexus:block_break"),
                rule(
                        "plexus:use_block",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerPlayerGameMode",
                        "useItemOn",
                        "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;"
                                + "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;"
                                + "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
                        new InvokeHookAtHead(INTERACTION_HOOKS, "onUseItemOn", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:use_item",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerPlayerGameMode",
                        "useItem",
                        "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;"
                                + "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)"
                                + "Lnet/minecraft/world/InteractionResult;",
                        new InvokeHookAtHead(INTERACTION_HOOKS, "onUseItem", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:use_entity",
                        ALL_SIDES,
                        "net/minecraft/world/entity/player/Player",
                        "interactOn",
                        "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/InteractionHand;)"
                                + "Lnet/minecraft/world/InteractionResult;",
                        new InvokeHookAtHead(INTERACTION_HOOKS, "onInteractOn", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:attack_entity",
                        ALL_SIDES,
                        "net/minecraft/world/entity/player/Player",
                        "attack",
                        "(Lnet/minecraft/world/entity/Entity;)V",
                        new InvokeHookAtHead(INTERACTION_HOOKS, "onAttack", HeadHookResult.CANCEL_IF_TRUE)),
                // Dig start only (START_DESTROY_BLOCK); the hook lets every other action through.
                rule(
                        "plexus:attack_block",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerPlayerGameMode",
                        "handleBlockBreakAction",
                        "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;"
                                + "Lnet/minecraft/core/Direction;II)V",
                        new InvokeHookAtHead(INTERACTION_HOOKS, "onBlockBreakAction", HeadHookResult.CANCEL_IF_TRUE)),
                rule(
                        "plexus:block_place",
                        ALL_SIDES,
                        "net/minecraft/world/item/BlockItem",
                        "place",
                        "(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
                        new RedirectCall(
                                new MethodTarget(
                                        "net/minecraft/world/item/BlockItem",
                                        "placeBlock",
                                        "(Lnet/minecraft/world/item/context/BlockPlaceContext;"
                                                + "Lnet/minecraft/world/level/block/state/BlockState;)Z"),
                                0,
                                INTERACTION_HOOKS,
                                "onPlaceBlock")),
                rule(
                        "plexus:block_placed",
                        ALL_SIDES,
                        "net/minecraft/world/item/BlockItem",
                        "place",
                        "(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
                        new InvokeHookBeforeReturn(INTERACTION_HOOKS, "onPlaceReturn"),
                        "plexus:block_place"),
                rule(
                        "plexus:level_tick",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerLevel",
                        "tick",
                        "(Ljava/util/function/BooleanSupplier;)V",
                        new InvokeHookAtHead(GAME_HOOKS, "onLevelTick", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:level_tick_end",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerLevel",
                        "tick",
                        "(Ljava/util/function/BooleanSupplier;)V",
                        new InvokeHookBeforeReturn(GAME_HOOKS, "onLevelTickEnd"),
                        "plexus:level_tick"),
                rule(
                        "plexus:entity_attributes",
                        ALL_SIDES,
                        "net/minecraft/world/entity/ai/attributes/DefaultAttributes",
                        "getSupplier",
                        "(Lnet/minecraft/world/entity/EntityType;)Lnet/minecraft/world/entity/ai/attributes/AttributeSupplier;",
                        new InvokeHookAtHead(GAME_HOOKS, "onGetAttributes", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:entity_has_attributes",
                        ALL_SIDES,
                        "net/minecraft/world/entity/ai/attributes/DefaultAttributes",
                        "hasSupplier",
                        "(Lnet/minecraft/world/entity/EntityType;)Z",
                        new InvokeHookAtHead(GAME_HOOKS, "onHasAttributes", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:entity_damage",
                        ALL_SIDES,
                        "net/minecraft/world/entity/LivingEntity",
                        "hurt",
                        "(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
                        new InvokeHookAtHead(GAME_HOOKS, "onHurt", HeadHookResult.RETURN_IF_NON_NULL)),
                // checkTotemDeathProtection is private and has the same descriptor in every supported version (javap);
                // the
                // game calls it only after lethal damage, before die. TRUE means the entity was saved.
                // actuallyHurt ends with this.gameEvent(...) in both classes (javap); the hook reads the health lost
                // since the hurt hook.
                rule(
                        "plexus:entity_after_damage",
                        ALL_SIDES,
                        "net/minecraft/world/entity/LivingEntity",
                        "actuallyHurt",
                        "(Lnet/minecraft/world/damagesource/DamageSource;F)V",
                        new InvokeHookAtCall(
                                new MethodTarget(
                                        "net/minecraft/world/entity/LivingEntity",
                                        "gameEvent",
                                        "(Lnet/minecraft/world/level/gameevent/GameEvent;)V"),
                                0,
                                InvokeHookAtCall.Timing.AFTER,
                                GAME_HOOKS,
                                "onDamageApplied")),
                rule(
                        "plexus:player_after_damage",
                        ALL_SIDES,
                        "net/minecraft/world/entity/player/Player",
                        "actuallyHurt",
                        "(Lnet/minecraft/world/damagesource/DamageSource;F)V",
                        new InvokeHookAtCall(
                                new MethodTarget(
                                        "net/minecraft/world/entity/player/Player",
                                        "gameEvent",
                                        "(Lnet/minecraft/world/level/gameevent/GameEvent;)V"),
                                0,
                                InvokeHookAtCall.Timing.AFTER,
                                GAME_HOOKS,
                                "onPlayerDamageApplied")),
                // Every roll of a loot table passes this public method (block, mob, chest, fishing); the hook rolls the
                // table itself, lets LootEvents.MODIFY edit the items, and hands them to the sink (javap).
                rule(
                        "plexus:loot_modify",
                        ALL_SIDES,
                        "net/minecraft/world/level/storage/loot/LootTable",
                        "getRandomItems",
                        "(Lnet/minecraft/world/level/storage/loot/LootContext;Ljava/util/function/Consumer;)V",
                        new InvokeHookAtHead(GAME_HOOKS, "onLootRoll", HeadHookResult.CANCEL_IF_TRUE)),
                // PlayerList.broadcastChatMessage(PlayerChatMessage, ServerPlayer, ChatType$Bound) is public and has
                // the
                // same descriptor in every supported version (javap); it is where typed chat is delivered.
                rule(
                        "plexus:chat_message",
                        ALL_SIDES,
                        "net/minecraft/server/players/PlayerList",
                        "broadcastChatMessage",
                        "(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;"
                                + "Lnet/minecraft/network/chat/ChatType$Bound;)V",
                        new InvokeHookAtHead(CHAT_HOOKS, "onBroadcastChat", HeadHookResult.CANCEL_IF_TRUE)),
                // Every system message to a player (command feedback, announcements) passes this public method (javap).
                rule(
                        "plexus:system_message",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerPlayer",
                        "sendSystemMessage",
                        "(Lnet/minecraft/network/chat/Component;Z)V",
                        new InvokeHookAtHead(CHAT_HOOKS, "onSendSystemMessage", HeadHookResult.CANCEL_IF_TRUE)),
                // LivingEntity.onEquipItem(EquipmentSlot, ItemStack, ItemStack) is public with one descriptor (javap).
                rule(
                        "plexus:entity_equipment_changed",
                        ALL_SIDES,
                        "net/minecraft/world/entity/LivingEntity",
                        "onEquipItem",
                        "(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;"
                                + "Lnet/minecraft/world/item/ItemStack;)V",
                        new InvokeHookAtHead(
                                EQUIPMENT_HOOKS,
                                "onEquipItem",
                                HeadHookResult.CONTINUE)), // Sleep and gliding (javap): startSleepInBed is declared by
                // ServerPlayer; startSleeping and
                // stopSleeping
                // by LivingEntity (Player and ServerPlayer override them and call super); tryToStartFallFlying by
                // Player.
                rule(
                        "plexus:player_allow_sleeping",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerPlayer",
                        "startSleepInBed",
                        "(Lnet/minecraft/core/BlockPos;)Lcom/mojang/datafixers/util/Either;",
                        new InvokeHookAtHead(
                                SLEEP_GLIDE_HOOKS, "onStartSleepInBed", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:entity_started_sleeping",
                        ALL_SIDES,
                        "net/minecraft/world/entity/LivingEntity",
                        "startSleeping",
                        "(Lnet/minecraft/core/BlockPos;)V",
                        new InvokeHookBeforeReturn(SLEEP_GLIDE_HOOKS, "onStartSleeping")),
                rule(
                        "plexus:entity_stopping_sleep",
                        ALL_SIDES,
                        "net/minecraft/world/entity/LivingEntity",
                        "stopSleeping",
                        "()V",
                        new InvokeHookAtHead(SLEEP_GLIDE_HOOKS, "onStopSleeping", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:player_allow_fall_flying",
                        ALL_SIDES,
                        "net/minecraft/world/entity/player/Player",
                        "tryToStartFallFlying",
                        "()Z",
                        new InvokeHookBeforeReturn(SLEEP_GLIDE_HOOKS, "onTryToStartFallFlying")),
                rule(
                        "plexus:entity_drops",
                        ALL_SIDES,
                        "net/minecraft/world/entity/LivingEntity",
                        "dropAllDeathLoot",
                        "(Lnet/minecraft/world/damagesource/DamageSource;)V",
                        new InvokeHookAtHead(GAME_HOOKS, "onDropAllDeathLoot", HeadHookResult.CANCEL_IF_TRUE)),
                // getFuel builds a cached table that isFuel and getBurnDuration both read (javap); it has no
                // parameters.
                rule(
                        "plexus:furnace_fuel",
                        ALL_SIDES,
                        "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity",
                        "getFuel",
                        "()Ljava/util/Map;",
                        new InvokeHookBeforeReturn(GAME_HOOKS, "onFuelTable")),
                rule(
                        "plexus:entity_allow_death",
                        ALL_SIDES,
                        "net/minecraft/world/entity/LivingEntity",
                        "checkTotemDeathProtection",
                        "(Lnet/minecraft/world/damagesource/DamageSource;)Z",
                        new InvokeHookAtHead(GAME_HOOKS, "onCheckDeathProtection", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:entity_death",
                        ALL_SIDES,
                        "net/minecraft/world/entity/LivingEntity",
                        "die",
                        "(Lnet/minecraft/world/damagesource/DamageSource;)V",
                        new InvokeHookAtHead(GAME_HOOKS, "onDie", HeadHookResult.CONTINUE)),
                // PlayerEvents.TICK; ServerPlayer exists in the client jar but is only ticked by a server.
                rule(
                        "plexus:player_tick",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerPlayer",
                        "tick",
                        "()V",
                        new InvokeHookAtHead(GAME_HOOKS, "onPlayerTick", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:player_death",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerPlayer",
                        "die",
                        "(Lnet/minecraft/world/damagesource/DamageSource;)V",
                        new InvokeHookAtHead(GAME_HOOKS, "onPlayerDie", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:client_entity_renderers",
                        CLIENT_ONLY,
                        "net/minecraft/client/renderer/entity/EntityRenderers",
                        "createEntityRenderers",
                        "(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)Ljava/util/Map;",
                        new InvokeHookBeforeReturn(CLIENT_HOOKS, "onCreateEntityRenderers")),
                rule(
                        "plexus:client_configuration_payloads",
                        CLIENT_ONLY,
                        "net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl",
                        "handleCustomPayload",
                        "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",
                        new InvokeHookAtHead(CLIENT_HOOKS, "onConfigurationPayload", HeadHookResult.CANCEL_IF_TRUE)),
                rule(
                        "plexus:client_payloads",
                        CLIENT_ONLY,
                        "net/minecraft/client/multiplayer/ClientPacketListener",
                        "handleCustomPayload",
                        "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",
                        new InvokeHookAtHead(CLIENT_HOOKS, "onClientPayload", HeadHookResult.CANCEL_IF_TRUE)),
                rule(
                        "plexus:client_item_tooltip",
                        CLIENT_ONLY,
                        "net/minecraft/world/item/ItemStack",
                        "getTooltipLines",
                        "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)"
                                + "Ljava/util/List;",
                        new InvokeHookBeforeReturn(
                                "dev/plexusmodloader/game/layer/v1_20_4/client/ItemTooltips", "onTooltipLines")),
                rule(
                        "plexus:client_key_bindings",
                        CLIENT_ONLY,
                        "net/minecraft/client/Options",
                        "load",
                        "()V",
                        new InvokeHookAtHead(CLIENT_HOOKS, "onOptionsLoad", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:client_tick",
                        CLIENT_ONLY,
                        "net/minecraft/client/Minecraft",
                        "tick",
                        "()V",
                        new InvokeHookBeforeReturn(CLIENT_HOOKS, "onClientTick")),
                rule(
                        "plexus:client_key_input",
                        CLIENT_ONLY,
                        "net/minecraft/client/KeyboardHandler",
                        "keyPress",
                        "(JIIII)V",
                        new InvokeHookAtHead(CLIENT_HOOKS, "onKeyPress", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:client_mouse_button",
                        CLIENT_ONLY,
                        "net/minecraft/client/MouseHandler",
                        "onPress",
                        "(JIII)V",
                        new InvokeHookAtHead(CLIENT_HOOKS, "onMouseButton", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:client_mouse_scroll",
                        CLIENT_ONLY,
                        "net/minecraft/client/MouseHandler",
                        "onScroll",
                        "(JDD)V",
                        new InvokeHookAtHead(CLIENT_HOOKS, "onMouseScroll", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:client_particle_providers",
                        CLIENT_ONLY,
                        "net/minecraft/client/particle/ParticleEngine",
                        "registerProviders",
                        "()V",
                        new InvokeHookBeforeReturn(CLIENT_HOOKS, "onRegisterParticleProviders")),
                rule(
                        "plexus:client_block_entity_renderers",
                        CLIENT_ONLY,
                        "net/minecraft/client/renderer/blockentity/BlockEntityRenderers",
                        "createEntityRenderers",
                        "(Lnet/minecraft/client/renderer/blockentity/BlockEntityRendererProvider$Context;)"
                                + "Ljava/util/Map;",
                        new InvokeHookBeforeReturn(CLIENT_HOOKS, "onCreateBlockEntityRenderers")),
                rule(
                        "plexus:client_asset_reload",
                        CLIENT_ONLY,
                        "net/minecraft/server/packs/resources/ReloadableResourceManager",
                        "<init>",
                        "(Lnet/minecraft/server/packs/PackType;)V",
                        new InvokeHookBeforeReturn(CLIENT_HOOKS, "onResourceManagerCreated")),
                rule(
                        "plexus:client_joined_world",
                        CLIENT_ONLY,
                        "net/minecraft/client/multiplayer/ClientPacketListener",
                        "handleLogin",
                        "(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V",
                        new InvokeHookBeforeReturn(CLIENT_HOOKS, "onClientLogin")),
                rule(
                        "plexus:client_left_world",
                        CLIENT_ONLY,
                        "net/minecraft/client/Minecraft",
                        "disconnect",
                        "(Lnet/minecraft/client/gui/screens/Screen;)V",
                        new InvokeHookAtHead(CLIENT_HOOKS, "onClientDisconnect", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:client_menu_screens",
                        CLIENT_ONLY,
                        "net/minecraft/client/gui/screens/MenuScreens",
                        "create",
                        "(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/client/Minecraft;I"
                                + "Lnet/minecraft/network/chat/Component;)V",
                        new InvokeHookAtHead(CLIENT_HOOKS, "onCreateMenuScreen", HeadHookResult.CANCEL_IF_TRUE)),
                rule(
                        "plexus:chunk_loaded",
                        ALL_SIDES,
                        "net/minecraft/world/level/chunk/LevelChunk",
                        "registerAllBlockEntitiesAfterLevelLoad",
                        "()V",
                        new InvokeHookAtHead(GAME_HOOKS, "onChunkLoaded", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:chunk_unloaded",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerLevel",
                        "unload",
                        "(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
                        new InvokeHookAtHead(GAME_HOOKS, "onChunkUnloaded", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:server_reload_listeners",
                        ALL_SIDES,
                        "net/minecraft/server/ReloadableServerResources",
                        "listeners",
                        "()Ljava/util/List;",
                        new InvokeHookBeforeReturn(GAME_HOOKS, "onServerReloadListeners")),
                rule(
                        "plexus:entity_data_save",
                        ALL_SIDES,
                        "net/minecraft/world/entity/Entity",
                        "saveWithoutId",
                        "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;",
                        new InvokeHookBeforeReturn(GAME_HOOKS, "onEntitySaved")),
                rule(
                        "plexus:entity_data_load",
                        ALL_SIDES,
                        "net/minecraft/world/entity/Entity",
                        "load",
                        "(Lnet/minecraft/nbt/CompoundTag;)V",
                        new InvokeHookBeforeReturn(GAME_HOOKS, "onEntityLoaded")),
                rule(
                        "plexus:player_respawn_data",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerPlayer",
                        "restoreFrom",
                        "(Lnet/minecraft/server/level/ServerPlayer;Z)V",
                        new InvokeHookBeforeReturn(GAME_HOOKS, "onPlayerRestored")),
                rule(
                        "plexus:entity_tracking_data",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerEntity",
                        "addPairing",
                        "(Lnet/minecraft/server/level/ServerPlayer;)V",
                        new InvokeHookBeforeReturn(GAME_HOOKS, "onStartTracking")),
                rule(
                        "plexus:login_check",
                        ALL_SIDES,
                        "net/minecraft/server/players/PlayerList",
                        "canPlayerLogin",
                        "(Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/network/chat/Component;",
                        new InvokeHookAtHead(GAME_HOOKS, "onCanPlayerLogin", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:worldgen_features",
                        ALL_SIDES,
                        "net/minecraft/server/MinecraftServer",
                        "createLevels",
                        "(Lnet/minecraft/server/level/progress/ChunkProgressListener;)V",
                        new InvokeHookAtHead(GAME_HOOKS, "onCreateLevels", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:world_loaded",
                        ALL_SIDES,
                        "net/minecraft/server/MinecraftServer",
                        "createLevels",
                        "(Lnet/minecraft/server/level/progress/ChunkProgressListener;)V",
                        new InvokeHookBeforeReturn(LIFECYCLE_HOOKS, "onLevelsCreated"),
                        "plexus:worldgen_features"),
                rule(
                        "plexus:world_saving",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerLevel",
                        "save",
                        "(Lnet/minecraft/util/ProgressListener;ZZ)V",
                        new InvokeHookAtHead(LIFECYCLE_HOOKS, "onLevelSave", HeadHookResult.CONTINUE)),
                rule(
                        "plexus:world_unloaded",
                        ALL_SIDES,
                        "net/minecraft/server/MinecraftServer",
                        "stopServer",
                        "()V",
                        new InvokeHookAtCall(
                                new MethodTarget("net/minecraft/server/level/ServerLevel", "close", "()V"),
                                InvokeHookAtCall.ALL,
                                InvokeHookAtCall.Timing.BEFORE,
                                LIFECYCLE_HOOKS,
                                "onCloseLevel"),
                        "plexus:server_stopping_event"),
                rule(
                        "plexus:server_stopped",
                        ALL_SIDES,
                        "net/minecraft/server/MinecraftServer",
                        "stopServer",
                        "()V",
                        new InvokeHookBeforeReturn(LIFECYCLE_HOOKS, "onServerStopped"),
                        "plexus:world_unloaded"),
                rule(
                        "plexus:player_respawned",
                        ALL_SIDES,
                        "net/minecraft/server/players/PlayerList",
                        "respawn",
                        "(Lnet/minecraft/server/level/ServerPlayer;Z)Lnet/minecraft/server/level/ServerPlayer;",
                        new InvokeHookBeforeReturn(LIFECYCLE_HOOKS, "onPlayerRespawned")),
                rule(
                        "plexus:entity_joined_world",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerLevel$EntityCallbacks",
                        "onTrackingStart",
                        "(Lnet/minecraft/world/entity/Entity;)V",
                        new InvokeHookAtCall(
                                new MethodTarget(
                                        "net/minecraft/server/level/ServerChunkCache",
                                        "addEntity",
                                        "(Lnet/minecraft/world/entity/Entity;)V"),
                                0,
                                InvokeHookAtCall.Timing.AFTER,
                                LIFECYCLE_HOOKS,
                                "onEntityTracked")),
                // onTrackingEnd calls ServerChunkCache.removeEntity first (javap); the hook runs before it, while the
                // entity is still tracked. EntityCallbacks is package-private, as for plexus:entity_joined_world.
                rule(
                        "plexus:entity_left_world",
                        ALL_SIDES,
                        "net/minecraft/server/level/ServerLevel$EntityCallbacks",
                        "onTrackingEnd",
                        "(Lnet/minecraft/world/entity/Entity;)V",
                        new InvokeHookAtCall(
                                new MethodTarget(
                                        "net/minecraft/server/level/ServerChunkCache",
                                        "removeEntity",
                                        "(Lnet/minecraft/world/entity/Entity;)V"),
                                0,
                                InvokeHookAtCall.Timing.BEFORE,
                                LIFECYCLE_HOOKS,
                                "onEntityUntracked")),
                rule(
                        "plexus:mod_dimension_stability",
                        ALL_SIDES,
                        "net/minecraft/world/level/levelgen/WorldDimensions",
                        "checkStability",
                        "(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/dimension/LevelStem;)"
                                + "Lcom/mojang/serialization/Lifecycle;",
                        new InvokeHookAtHead(
                                GAME_HOOKS, "onCheckDimensionStability", HeadHookResult.RETURN_IF_NON_NULL)),
                rule(
                        "plexus:mod_dimension_count",
                        ALL_SIDES,
                        "net/minecraft/world/level/levelgen/WorldDimensions",
                        "bake",
                        "(Lnet/minecraft/core/Registry;)Lnet/minecraft/world/level/levelgen/WorldDimensions$Complete;",
                        new RedirectCall(
                                new MethodTarget("java/util/List", "size", "()I"),
                                0,
                                GAME_HOOKS,
                                "onBakedDimensionCount")),
                rule(
                        "plexus:creative_tabs",
                        ALL_SIDES,
                        "net/minecraft/world/item/CreativeModeTab",
                        "buildContents",
                        "(Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;)V",
                        new InvokeHookBeforeReturn(GAME_HOOKS, "onCreativeTabBuilt")),
                rule(
                        "plexus:creative_tab_pages",
                        ALL_SIDES,
                        "net/minecraft/world/item/CreativeModeTabs",
                        "validate",
                        "()V",
                        new InvokeHookAtHead(GAME_HOOKS, "onValidateCreativeTabs", HeadHookResult.CANCEL_IF_TRUE)),
                creativeTabsRedirect("plexus:client_creative_tabs_clicked", "mouseClicked", "(DDI)Z"),
                creativeTabsRedirect("plexus:client_creative_tabs_released", "mouseReleased", "(DDI)Z"),
                creativeTabsRedirect(
                        "plexus:client_creative_tabs_hovered",
                        "render",
                        "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"),
                creativeTabsRedirect(
                        "plexus:client_creative_tabs_drawn",
                        "renderBg",
                        "(Lnet/minecraft/client/gui/GuiGraphics;FII)V"),
                rule(
                        "plexus:client_creative_page_buttons",
                        CLIENT_ONLY,
                        CREATIVE_SCREEN,
                        "init",
                        "()V",
                        new InvokeHookBeforeReturn(CREATIVE_PAGING, "onScreenInit")),
                rule(
                        "plexus:client_hud",
                        CLIENT_ONLY,
                        "net/minecraft/client/gui/Gui",
                        "render",
                        "(Lnet/minecraft/client/gui/GuiGraphics;F)V",
                        new InvokeHookBeforeReturn(CLIENT_HOOKS, "onRenderHud")),
                rule(
                        "plexus:client_world_painters",
                        CLIENT_ONLY,
                        "net/minecraft/client/renderer/LevelRenderer",
                        "renderLevel",
                        "(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
                        new InvokeHookBeforeReturn(CLIENT_HOOKS, "onRenderLevel")),
                rule(
                        "plexus:client_fov",
                        CLIENT_ONLY,
                        "net/minecraft/client/renderer/GameRenderer",
                        "getFov",
                        "(Lnet/minecraft/client/Camera;FZ)D",
                        new InvokeHookBeforeReturn(CLIENT_HOOKS, "onGetFov")));
    }

    private static TransformRuleDefinition creativeTabsRedirect(String ruleId, String method, String descriptor) {
        return new TransformRuleDefinition(
                ruleId,
                "plexus",
                GAME_VERSION,
                CLIENT_ONLY,
                new MethodTarget(CREATIVE_SCREEN, method, descriptor),
                new RedirectCall(
                        new MethodTarget("net/minecraft/world/item/CreativeModeTabs", "tabs", "()Ljava/util/List;"),
                        0,
                        CREATIVE_PAGING,
                        "onListTabs"),
                TransformFailurePolicy.ABORT_STARTUP);
    }

    private static List<TransformRuleDefinition> modInitializationRules() {
        MethodTarget timerHack = new MethodTarget("net/minecraft/Util", "startTimerHackThread", "()V");
        return List.of(
                rule(
                        "plexus:mod_initialization_server",
                        ALL_SIDES,
                        "net/minecraft/server/Main",
                        "main",
                        "([Ljava/lang/String;)V",
                        new InvokeHookAtCall(
                                timerHack, 0, InvokeHookAtCall.Timing.BEFORE, HOOKS, "onModInitialization")),
                rule(
                        "plexus:mod_initialization_client",
                        CLIENT_ONLY,
                        "net/minecraft/client/Minecraft",
                        "<init>",
                        "(Lnet/minecraft/client/main/GameConfig;)V",
                        new InvokeHookAtCall(
                                new MethodTarget("java/lang/Thread", "currentThread", "()Ljava/lang/Thread;"),
                                0,
                                InvokeHookAtCall.Timing.BEFORE,
                                HOOKS,
                                "onModInitialization")));
    }

    private static List<TransformRuleDefinition> brandAndServerRules() {
        return List.of(
                new TransformRuleDefinition(
                        "plexus:client_brand",
                        "plexus",
                        GAME_VERSION,
                        CLIENT_ONLY,
                        new MethodTarget(
                                "net/minecraft/client/ClientBrandRetriever",
                                "getClientModName",
                                "()Ljava/lang/String;"),
                        new ReplaceConstantReturnWithStaticCall("vanilla", HOOKS, "clientModName"),
                        TransformFailurePolicy.DISABLE_RULE),
                new TransformRuleDefinition(
                        "plexus:server_brand",
                        "plexus",
                        GAME_VERSION,
                        ALL_SIDES,
                        new MethodTarget(
                                "net/minecraft/server/MinecraftServer", "getServerModName", "()Ljava/lang/String;"),
                        new ReplaceConstantReturnWithStaticCall("vanilla", HOOKS, "serverModName"),
                        TransformFailurePolicy.DISABLE_RULE),
                new TransformRuleDefinition(
                        "plexus:server_tick_events",
                        "plexus",
                        GAME_VERSION,
                        ALL_SIDES,
                        new MethodTarget(
                                "net/minecraft/server/MinecraftServer",
                                "tickServer",
                                "(Ljava/util/function/BooleanSupplier;)V"),
                        new InjectStaticCallAtHead(HOOKS, "onServerTick"),
                        TransformFailurePolicy.DISABLE_RULE),
                new TransformRuleDefinition(
                        "plexus:server_tick_end",
                        "plexus",
                        GAME_VERSION,
                        ALL_SIDES,
                        new MethodTarget(
                                "net/minecraft/server/MinecraftServer",
                                "tickServer",
                                "(Ljava/util/function/BooleanSupplier;)V"),
                        new InvokeHookBeforeReturn(LIFECYCLE_HOOKS, "onServerTickEnd"),
                        TransformFailurePolicy.DISABLE_RULE,
                        List.of("plexus:server_tick_events")),
                new TransformRuleDefinition(
                        "plexus:server_running",
                        "plexus",
                        GAME_VERSION,
                        ALL_SIDES,
                        new MethodTarget("net/minecraft/server/MinecraftServer", "runServer", "()V"),
                        new InjectStaticCallAtHead(HOOKS, "onServerRunning"),
                        TransformFailurePolicy.DISABLE_RULE),
                new TransformRuleDefinition(
                        "plexus:server_stopping_event",
                        "plexus",
                        GAME_VERSION,
                        ALL_SIDES,
                        new MethodTarget("net/minecraft/server/MinecraftServer", "stopServer", "()V"),
                        new InjectStaticCallAtHead(HOOKS, "onServerStopping"),
                        TransformFailurePolicy.DISABLE_RULE));
    }

    private static TransformRuleDefinition rule(
            String ruleId,
            Set<GameSide> sides,
            String targetOwner,
            String targetName,
            String targetDescriptor,
            MethodTransformAction action) {
        return new TransformRuleDefinition(
                ruleId,
                "plexus",
                GAME_VERSION,
                sides,
                new MethodTarget(targetOwner, targetName, targetDescriptor),
                action,
                TransformFailurePolicy.ABORT_STARTUP);
    }

    private static TransformRuleDefinition rule(
            String ruleId,
            Set<GameSide> sides,
            String targetOwner,
            String targetName,
            String targetDescriptor,
            MethodTransformAction action,
            String... dependsOn) {
        return new TransformRuleDefinition(
                ruleId,
                "plexus",
                GAME_VERSION,
                sides,
                new MethodTarget(targetOwner, targetName, targetDescriptor),
                action,
                TransformFailurePolicy.ABORT_STARTUP,
                List.of(dependsOn));
    }
}
