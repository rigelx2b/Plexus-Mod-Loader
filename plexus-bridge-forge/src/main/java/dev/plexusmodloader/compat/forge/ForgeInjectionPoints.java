package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.compat.bridge.InjectionPointReplacement;
import java.util.List;

/**
 * Where MinecraftForge's patch moved vanilla calls that other loaders' Mixins inject at (ADR-0021 G8), for {@link
 * ForgeGameHost#injectionPointReplacements()}. Each was checked with {@code javap} against the vanilla and the patched
 * classes of the pinned Forge releases 26.1-62.0.9 and 26.2-65.1.5 (the furnace rules at the end: 1.21.1-52.1.21), and
 * {@code ForgeInjectionPointsTest} checks them
 * against a patched client jar. The bridge that uses them checks the patched classes again before it moves anything.
 */
final class ForgeInjectionPoints {

    private static final String GAME_MODE = "net/minecraft/server/level/ServerPlayerGameMode";
    /** fabric-lifecycle-events-v1's {@code @Inject} handler into {@code setBlockEntity}, in Mojang's names. */
    private static final String SET_BLOCK_ENTITY_UNLOAD_HANDLER =
            "(Lnet/minecraft/world/level/block/entity/BlockEntity;"
                    + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/entity/BlockEntity;)V";

    private static final String WORLD_REGISTRIES =
            "Lnet/minecraft/resources/RegistryDataLoader;WORLD_REGISTRIES:Ljava/util/List;";
    private static final String WORLD_AND_DIMENSION_REGISTRIES =
            "Lnet/minecraft/resources/RegistryDataLoader;getWorldAndDimensionRegistries()Ljava/util/List;";

    private ForgeInjectionPoints() {}

    static List<InjectionPointReplacement> all() {
        return List.of(
                // Bootstrap.bootStrap calls GameData.vanillaSnapshot() at the end where vanilla calls wrapStreams()
                // (Forge wraps the streams elsewhere): the point at which Fabric API's registry synchronisation starts.
                new InjectionPointReplacement(
                        "net/minecraft/server/Bootstrap",
                        "bootStrap",
                        "Lnet/minecraft/server/Bootstrap;wrapStreams()V",
                        "Lnet/minecraftforge/registries/GameData;vanillaSnapshot()V"),
                // ServerPlayerGameMode.destroyBlock: vanilla calls Block.playerWillDestroy after the permission checks,
                // then removes the block. Forge calls it inside BlockState.onDestroyedByPlayer, from its new
                // removeBlock(BlockPos, boolean). The first call after the same checks, before anything changes (the
                // item, the block), is ServerPlayer.preventsBlockDrops(); the locals there (state, blockEntity) are
                // vanilla's. Fabric API's PlayerBlockBreakEvents.BEFORE (cancellable) runs there.
                new InjectionPointReplacement(
                        GAME_MODE,
                        "destroyBlock",
                        "Lnet/minecraft/world/level/block/Block;playerWillDestroy(Lnet/minecraft/world/level/Level;"
                                + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
                                + "Lnet/minecraft/world/entity/player/Player;)"
                                + "Lnet/minecraft/world/level/block/state/BlockState;",
                        "Lnet/minecraft/server/level/ServerPlayer;preventsBlockDrops()Z"),
                // Vanilla calls Block.destroy when removeBlock removed the block. Forge's removeBlock(BlockPos, Z)
                // calls it exactly then and returns whether it removed the block; destroyBlock calls it twice
                // (creative and survival). Fabric API's PlayerBlockBreakEvents.AFTER runs after each call that
                // returned true.
                new InjectionPointReplacement(
                        GAME_MODE,
                        "destroyBlock",
                        "Lnet/minecraft/world/level/block/Block;destroy(Lnet/minecraft/world/level/LevelAccessor;"
                                + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
                        "L" + GAME_MODE + ";removeBlock(Lnet/minecraft/core/BlockPos;Z)Z",
                        InjectionPointReplacement.Placement.AFTER_TRUE_RESULT),
                // ItemStack.useOn: vanilla calls Item.useOn(UseOnContext) where Fabric API's ItemStackMixin wraps the
                // call for ItemEvents.USE_ON. Forge moves the vanilla call into lambda$useOn$0.
                new InjectionPointReplacement(
                        "net/minecraft/world/item/ItemStack",
                        "useOn",
                        "Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
                        "lambda$useOn$0(Lnet/minecraft/world/item/context/UseOnContext;Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
                        InjectionPointReplacement.Placement.IN_METHOD),
                // Language.loadDefault: vanilla calls Map.copyOf(map) after loading the default translations, where
                // Fabric API's LanguageMixin.create loads Fabric mods' server translations into map. Forge replaces
                // Map.copyOf(map) with LanguageHook.captureLanguageMap(map) and keeps map mutable.
                new InjectionPointReplacement(
                        "net/minecraft/locale/Language",
                        "loadDefault",
                        "Ljava/util/Map;copyOf(Ljava/util/Map;)Ljava/util/Map;",
                        "Lnet/minecraftforge/server/LanguageHook;captureLanguageMap(Ljava/util/Map;)V"),
                // LivingEntity.getBedOrientation: vanilla calls BedBlock.getBedOrientation(BlockGetter, BlockPos).
                // Forge replaces it with BlockState.getBedDirection(LevelReader, BlockPos) for custom beds.
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/LivingEntity",
                        "getBedOrientation",
                        "Lnet/minecraft/world/level/block/BedBlock;getBedOrientation(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Direction;",
                        "Lnet/minecraft/world/level/block/state/BlockState;getBedDirection(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Direction;"),
                // LivingEntity.lambda$stopSleeping$0: vanilla calls Level.setBlock to clear OCCUPIED.
                // Forge replaces it with BlockState.setBedOccupied for custom beds.
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/LivingEntity",
                        "lambda$stopSleeping$0",
                        "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
                        "Lnet/minecraft/world/level/block/state/BlockState;setBedOccupied(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;Z)V"),
                // The same on 26.3, where vanilla's lambda$stopSleeping$0 calls Level.setBlockAndUpdate (checked with
                // javap on the 26.3 client and Forge 26.3-66.1.0).
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/LivingEntity",
                        "lambda$stopSleeping$0",
                        "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
                        "Lnet/minecraft/world/level/block/state/BlockState;setBedOccupied(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;Z)V"),
                // TagsProvider.lambda$run$5: vanilla saves stable JSON via DataProvider.saveStable.
                // Forge moves this tag-saving lambda to lambda$run$6.
                new InjectionPointReplacement(
                        "net/minecraft/data/tags/TagsProvider",
                        "lambda$run$5",
                        "Lnet/minecraft/data/DataProvider;saveStable(Lnet/minecraft/data/CachedOutput;Lnet/minecraft/core/HolderLookup$Provider;Lcom/mojang/serialization/Codec;Ljava/lang/Object;Ljava/nio/file/Path;)Ljava/util/concurrent/CompletableFuture;",
                        "lambda$run$6",
                        InjectionPointReplacement.Placement.IN_METHOD),
                // TagsProvider.lambda$run$5: vanilla creates TagFile with (List, boolean).
                // Forge moves this to lambda$run$6 and widens TagFile.<init> to (List, boolean, List).
                new InjectionPointReplacement(
                        "net/minecraft/data/tags/TagsProvider",
                        "lambda$run$5",
                        "Lnet/minecraft/tags/TagFile;<init>(Ljava/util/List;Z)V",
                        "lambda$run$6:Lnet/minecraft/tags/TagFile;<init>(Ljava/util/List;ZLjava/util/List;)V",
                        InjectionPointReplacement.Placement.IN_METHOD),
                // Entity.updateSwimming: vanilla checks Entity.isInWater(). Forge widens to isInWaterOrSwimmable().
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/Entity",
                        "updateSwimming",
                        "Lnet/minecraft/world/entity/Entity;isInWater()Z",
                        "Lnet/minecraft/world/entity/Entity;isInWaterOrSwimmable()Z"),
                // Entity.isVisuallyCrawling: vanilla checks Entity.isInWater(). Forge widens to isInWaterOrSwimmable().
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/Entity",
                        "isVisuallyCrawling",
                        "Lnet/minecraft/world/entity/Entity;isInWater()Z",
                        "Lnet/minecraft/world/entity/Entity;isInWaterOrSwimmable()Z"),
                // MultiPlayerGameMode.sameDestroyTarget: vanilla asks ItemStack.isSameItemSameComponents(mainHand,
                // destroyingItem); Forge asks !destroyingItem.shouldCauseBlockBreakReset(mainHand) (javap, 26.2-65.1.5
                // and 26.3-66.1.0). fabric-item-api-v1's client MultiPlayerGameModeMixin redirects the vanilla call;
                // the
                // bridge answers Forge's opposite question (HostPatchAdjustments' block break reset item wrap).
                new InjectionPointReplacement(
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode",
                        "sameDestroyTarget",
                        "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
                        "Lnet/minecraft/world/item/ItemStack;shouldCauseBlockBreakReset(Lnet/minecraft/world/item/ItemStack;)Z"),
                // Screen input (javap, 26.2-65.1.5 and 26.3-66.1.0): where vanilla calls the screen's own handler,
                // Forge
                // calls a static helper with the screen first, which posts its ScreenEvent before and after making that
                // very call. fabric-screen-api-v1's KeyboardHandlerMixin and MouseHandlerMixin wrap the vanilla calls
                // to
                // run Fabric's screen events; the wraps run on Forge's helpers (HostStaticCalls), and without them the
                // module did not load on the client.
                new InjectionPointReplacement(
                        "net/minecraft/client/KeyboardHandler",
                        "keyPress",
                        "Lnet/minecraft/client/gui/screens/Screen;keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
                        "Lnet/minecraftforge/client/ForgeHooksClient;onScreenKeyPressed(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/input/KeyEvent;)Z"),
                new InjectionPointReplacement(
                        "net/minecraft/client/KeyboardHandler",
                        "keyPress",
                        "Lnet/minecraft/client/gui/screens/Screen;keyReleased(Lnet/minecraft/client/input/KeyEvent;)Z",
                        "Lnet/minecraftforge/client/ForgeHooksClient;onScreenKeyReleased(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/input/KeyEvent;)Z"),
                new InjectionPointReplacement(
                        "net/minecraft/client/KeyboardHandler",
                        "charTyped",
                        "Lnet/minecraft/client/gui/screens/Screen;charTyped(Lnet/minecraft/client/input/CharacterEvent;)Z",
                        "Lnet/minecraftforge/client/ForgeHooksClient;onScreenCharTyped(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/input/CharacterEvent;)Z"),
                // Mouse clicks and releases: Forge's helper also takes the mouse position, before the vanilla
                // arguments.
                new InjectionPointReplacement(
                        "net/minecraft/client/MouseHandler",
                        "onButton",
                        "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
                        "Lnet/minecraftforge/client/event/ForgeEventFactoryClient;onScreenMouseClicked(Lnet/minecraft/client/gui/screens/Screen;DDLnet/minecraft/client/input/MouseButtonEvent;Z)Z"),
                new InjectionPointReplacement(
                        "net/minecraft/client/MouseHandler",
                        "onButton",
                        "Lnet/minecraft/client/gui/screens/Screen;mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z",
                        "Lnet/minecraftforge/client/event/ForgeEventFactoryClient;onScreenMouseReleased(Lnet/minecraft/client/gui/screens/Screen;DDLnet/minecraft/client/input/MouseButtonEvent;)Z"),
                new InjectionPointReplacement(
                        "net/minecraft/client/MouseHandler",
                        "handleAccumulatedMovement",
                        "Lnet/minecraft/client/gui/screens/Screen;mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z",
                        "Lnet/minecraftforge/client/ForgeHooksClient;onScreenMouseDrag(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/input/MouseButtonEvent;DD)Z"),
                // Gui.extractRenderState: while a screen is set, vanilla calls
                // screen.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, partialTick); Forge calls
                // this.drawScreen(graphics, mouseX, mouseY, partialTick), inherited from its ForgeLayerInstance, which
                // draws Forge's screen layers and then makes that very call for the current screen (javap, 26.2-65.1.5
                // and 26.3-66.1.0). fabric-screen-api-v1's GuiMixin wraps the vanilla call to run Fabric's
                // ScreenEvents.beforeExtract and afterExtract; the wrap runs on Forge's call with Gui.screen() as the
                // screen (HostTargetCalls), so Fabric's events also enclose Forge's layers below the screen.
                new InjectionPointReplacement(
                        "net/minecraft/client/gui/Gui",
                        "extractRenderState",
                        "Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
                        "Lnet/minecraft/client/gui/Gui;drawScreen(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"),
                // HangingSignEditScreen.<init>: vanilla builds the texture with Identifier.withDefaultNamespace(
                // "textures/gui/hanging_signs/" + woodType.name() + ".png"); Forge builds it with
                // Identifier.parse(woodType.name() + ".png").withPrefix("textures/gui/hanging_signs/") (javap,
                // 26.2-65.1.5 and 26.3-66.1.0), which keeps a mod's namespace in the wood type's name. That is what
                // fabric-object-builder-api-v1's HangingSignEditScreenMixin.init does (parse the name when it has a
                // namespace, put the path in the same folder); the result is the same identifier for every name.
                new InjectionPointReplacement(
                        "net/minecraft/client/gui/screens/inventory/HangingSignEditScreen",
                        "<init>",
                        "net/fabricmc/fabric/mixin/object/builder/client/HangingSignEditScreenMixin#init",
                        "Identifier.parse(woodType.name() + \".png\").withPrefix(\"textures/gui/hanging_signs/\")",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                // ModelProvider.ItemInfoCollector.finalizeAndValidate: vanilla gives every block item without a model
                // its block's model (the lambda that checks copies.containsKey); Forge removed that lambda and
                // validates only the items its data generator was given (javap, 26.2-65.1.5 and 26.3-66.1.0).
                // fabric-data-generation-api-v1's wrap of that containsKey limits the automatic models to the blocks of
                // the mod being generated; next to Forge no automatic models are made at all. Only data generation runs
                // this code. The module's other handler of the same name (the validation filter) still applies.
                new InjectionPointReplacement(
                        "net/minecraft/client/data/models/ModelProvider$ItemInfoCollector",
                        "finalizeAndValidate",
                        "net/fabricmc/fabric/mixin/datagen/client/ModelProviderItemInfoCollectorMixin"
                                + "#filterItemsForProcessingMod(Ljava/util/Map;Ljava/lang/Object;"
                                + "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z",
                        "its own item validation, which adds no automatic block item models",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                // SectionCompiler.compile: vanilla draws each block with ModelBlockRenderer.tesselateBlock(output, x,
                // y,
                // z, level, pos, state, model, seed); Forge calls its overload with one more argument, the ModelData it
                // looked up for that position and model (ModelData.EMPTY when there is none), javap, 26.2-65.1.5 and
                // 26.3-66.1.0. fabric-renderer-api-v1's SectionCompilerMixin redirects the vanilla call to draw the
                // block
                // through the Fabric renderer. The redirect runs where Forge passes ModelData.EMPTY; a block whose
                // model
                // Forge gives data (a Forge mod's connected textures, say) is drawn by Forge with it
                // (HostNeutralArguments). Without this the client stopped when it first drew a chunk (225).
                new InjectionPointReplacement(
                        "net/minecraft/client/renderer/chunk/SectionCompiler",
                        "compile",
                        "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V",
                        "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;JLnet/minecraftforge/client/model/data/ModelData;)V"
                                + "#Lnet/minecraftforge/client/model/data/ModelData;EMPTY",
                        InjectionPointReplacement.Placement.HOST_NEUTRAL_ARGUMENT),
                // Particle sprites by position (fabric-renderer-api-v1): vanilla asks
                // BlockStateModelSet.getParticleMaterial(state) for a block marker particle and for the block a
                // player's
                // view is inside; Fabric's renderer API asks the model with the level and the position instead. Forge
                // asks the model too, with the ModelData it keeps for that position (BlockMarker:
                // BlockStateModel.particleMaterial(ModelData); the view: its getOverlayBlock, which returns the state
                // with
                // its position), javap, 26.2-65.1.5 and 26.3-66.1.0. So the sprite follows the position the Forge way;
                // what differs is only that a Fabric renderer model that picks its particle sprite from the level gets
                // its default sprite in these two places (block marker particles and the in-wall overlay). Forgified
                // Fabric API leaves the in-wall capture out next to NeoForge as well. The captures are taken out; the
                // material wraps they feed then make the vanilla call.
                new InjectionPointReplacement(
                        "net/minecraft/client/particle/BlockMarker",
                        "<init>",
                        "net/fabricmc/fabric/mixin/client/renderer/block/particle/BlockMarkerMixin#getParticleMaterialProxy",
                        "BlockStateModel.particleMaterial(ModelData) with the level's model data at the marker",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                new InjectionPointReplacement(
                        "net/minecraft/client/renderer/ScreenEffectRenderer",
                        "getViewBlockingState",
                        "net/fabricmc/fabric/mixin/client/renderer/block/particle/ScreenEffectRendererMixin"
                                + "#onReturnGetInWallBlockState",
                        "getOverlayBlock(Player), which finds the state with its position",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                new InjectionPointReplacement(
                        "net/minecraft/client/renderer/extract/LevelExtractor",
                        "getViewBlockingState",
                        "net/fabricmc/fabric/mixin/client/renderer/block/particle/LevelExtractorMixin"
                                + "#captureViewBlockingPosition",
                        "getOverlayBlock(LocalPlayer, Frustum), which finds the state with its position",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                // LocalPlayer.shouldStopSwimSprinting: vanilla checks isInWater(); Forge widens it to
                // isInWaterOrSwimmable()
                // (javap, 26.2-65.1.5 and 26.3-66.1.0). fabric-content-registries-v0's LocalPlayerMixin keeps a player
                // sprint-swimming in a custom fluid there, and without this the module did not load on the client.
                new InjectionPointReplacement(
                        "net/minecraft/client/player/LocalPlayer",
                        "shouldStopSwimSprinting",
                        "Lnet/minecraft/client/player/LocalPlayer;isInWater()Z",
                        "Lnet/minecraft/client/player/LocalPlayer;isInWaterOrSwimmable()Z"),
                // LivingEntity.travelInFluid: vanilla calls travelInLava directly. Forge moves it to
                // travelInFluid(Vec3, FluidState).
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/LivingEntity",
                        "travelInFluid",
                        "Lnet/minecraft/world/entity/LivingEntity;travelInLava(Lnet/minecraft/world/phys/Vec3;DZD)V",
                        "travelInFluid(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/material/FluidState;)V",
                        InjectionPointReplacement.Placement.IN_METHOD),
                // AbstractBoat.checkInWater & isUnderwater: vanilla checks FluidState.is(TagKey). Forge calls
                // canBoatInFluid(FluidState).
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/vehicle/boat/AbstractBoat",
                        "checkInWater",
                        "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z",
                        "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;canBoatInFluid(Lnet/minecraft/world/level/material/FluidState;)Z"),
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/vehicle/boat/AbstractBoat",
                        "isUnderwater",
                        "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z",
                        "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;canBoatInFluid(Lnet/minecraft/world/level/material/FluidState;)Z"),
                // Entity.updateSwimming: vanilla checks standing FluidState.is(TagKey). Forge calls canStartSwimming().
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/Entity",
                        "updateSwimming",
                        "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z",
                        "Lnet/minecraft/world/entity/Entity;canStartSwimming()Z"),
                // LivingEntity.baseTick: vanilla checks LivingEntity.isEyeInFluid(TagKey). Forge calls
                // ForgeHooks.onLivingBreathe.
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/LivingEntity",
                        "baseTick",
                        "Lnet/minecraft/world/entity/LivingEntity;isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z",
                        "Lnet/minecraftforge/common/ForgeHooks;onLivingBreathe(Lnet/minecraft/world/entity/LivingEntity;II)V"),
                // LivingEntity.aiStep: vanilla calls LivingEntity.jumpInLiquid(TagKey). Forge calls
                // jumpInFluid(FluidType).
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/LivingEntity",
                        "aiStep",
                        "Lnet/minecraft/world/entity/LivingEntity;jumpInLiquid(Lnet/minecraft/tags/TagKey;)V",
                        "Lnet/minecraft/world/entity/LivingEntity;jumpInFluid(Lnet/minecraftforge/fluids/FluidType;)V"),
                // AbstractFurnaceBlockEntity.consumeFuel: vanilla calls Item.getCraftingRemainder(). Forge calls
                // ItemStack.getCraftingRemainder().
                new InjectionPointReplacement(
                        "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity",
                        "consumeFuel",
                        "Lnet/minecraft/world/item/Item;getCraftingRemainder()Lnet/minecraft/world/item/ItemStackTemplate;",
                        "Lnet/minecraft/world/item/ItemStack;getCraftingRemainder()Lnet/minecraft/world/item/ItemStackTemplate;"),
                // BrewingStandBlockEntity.doBrew: vanilla calls Item.getCraftingRemainder(). Forge calls
                // ItemStack.getCraftingRemainder().
                new InjectionPointReplacement(
                        "net/minecraft/world/level/block/entity/BrewingStandBlockEntity",
                        "doBrew",
                        "Lnet/minecraft/world/item/Item;getCraftingRemainder()Lnet/minecraft/world/item/ItemStackTemplate;",
                        "Lnet/minecraft/world/item/ItemStack;getCraftingRemainder()Lnet/minecraft/world/item/ItemStackTemplate;"),
                // CraftingRecipe.defaultCraftingReminder: vanilla calls ItemStack.getItem() and
                // Item.getCraftingRemainder(). Forge calls ItemStack.getCraftingRemainder().
                new InjectionPointReplacement(
                        "net/minecraft/world/item/crafting/CraftingRecipe",
                        "defaultCraftingReminder",
                        "Lnet/minecraft/world/item/ItemStack;getItem()Lnet/minecraft/world/item/Item;",
                        "Lnet/minecraft/world/item/ItemStack;getCraftingRemainder()Lnet/minecraft/world/item/ItemStackTemplate;"),
                new InjectionPointReplacement(
                        "net/minecraft/world/item/crafting/CraftingRecipe",
                        "defaultCraftingReminder",
                        "Lnet/minecraft/world/item/Item;getCraftingRemainder()Lnet/minecraft/world/item/ItemStackTemplate;",
                        "Lnet/minecraft/world/item/ItemStack;getCraftingRemainder()Lnet/minecraft/world/item/ItemStackTemplate;"),
                // EnchantmentHelper.lambda$getAvailableEnchantmentResults$0: vanilla calls
                // Enchantment.isPrimaryItem(ItemStack). Forge calls ItemStack.canApplyAtEnchantingTable(Holder).
                new InjectionPointReplacement(
                        "net/minecraft/world/item/enchantment/EnchantmentHelper",
                        "lambda$getAvailableEnchantmentResults$0",
                        "Lnet/minecraft/world/item/enchantment/Enchantment;isPrimaryItem(Lnet/minecraft/world/item/ItemStack;)Z",
                        "Lnet/minecraft/world/item/ItemStack;canApplyAtEnchantingTable(Lnet/minecraft/core/Holder;)Z"),
                // ServerPlayer.drop(boolean): vanilla drops the stack it removed from the selected slot with
                // drop(ItemStack, boolean, boolean). Forge calls ForgeHooks.onPlayerTossEvent(player, removed, true)
                // there, which makes that call itself (through Player.drop) and then fires ItemTossEvent; what comes
                // before (removing the stack, stopping item use) is vanilla's. Puzzles Lib's item toss event
                // (cancellable) runs before it. Forge also keeps the selected stack in a new local of the same type;
                // HostRenamedLocals makes an implicit @Local there capture vanilla's `removed` by name. This and the
                // next point were checked against 26.2-65.1.5 only; the bridge checks the patched class anyway.
                new InjectionPointReplacement(
                        "net/minecraft/server/level/ServerPlayer",
                        "drop",
                        "Lnet/minecraft/server/level/ServerPlayer;drop(Lnet/minecraft/world/item/ItemStack;ZZ)"
                                + "Lnet/minecraft/world/entity/item/ItemEntity;",
                        "Lnet/minecraftforge/common/ForgeHooks;onPlayerTossEvent(Lnet/minecraft/world/entity/player/"
                                + "Player;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/"
                                + "ItemEntity;"),
                // PersistentEntitySectionManager.addEntity(EntityAccess, boolean): Forge fires EntityJoinLevelEvent
                // and moves the vanilla body unchanged into addEntityWithoutEvent of the same descriptor (also called
                // by Forge's addNewEntityWithoutEvent, for ServerLevel.addPlayer). Architectury's entity add event
                // (cancellable, returning false) runs there before SectionPos.asLong.
                new InjectionPointReplacement(
                        "net/minecraft/world/level/entity/PersistentEntitySectionManager",
                        "addEntity",
                        "Lnet/minecraft/core/SectionPos;asLong(Lnet/minecraft/core/BlockPos;)J",
                        "addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z",
                        InjectionPointReplacement.Placement.IN_METHOD),
                // RegistryPatchGenerator.lambda$createLookup$0: vanilla reads RegistryDataLoader.WORLDGEN_REGISTRIES.
                // Forge calls RegistryDataLoader.getWorldGenAndDimensionStream().
                new InjectionPointReplacement(
                        "net/minecraft/data/registries/RegistryPatchGenerator",
                        "lambda$createLookup$0",
                        "Lnet/minecraft/resources/RegistryDataLoader;WORLDGEN_REGISTRIES:Ljava/util/List;",
                        "Lnet/minecraft/resources/RegistryDataLoader;getWorldGenAndDimensionStream()Ljava/util/stream/Stream;"),
                // FishingHook.shouldStopFishing (Forge 26.2-65.1.5, 26.3-66.1.0): vanilla keeps the hook while either
                // hand holds
                // ItemStack.is(Items.FISHING_ROD); Forge asks ItemStack.canPerformAction(ToolActions.FISHING_ROD_CAST)
                // at the same two places. Fabric API's allowModdedRods wraps the vanilla check.
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/projectile/FishingHook",
                        "shouldStopFishing",
                        "Lnet/minecraft/world/item/ItemStack;is(Ljava/lang/Object;)Z",
                        "Lnet/minecraft/world/item/ItemStack;canPerformAction(Lnet/minecraftforge/common/ToolAction;)Z"),
                // Mob.setTarget (checked on Forge 26.2-65.1.5 and 26.3-66.1.0): vanilla assigns target =
                // asValidTarget(target); Forge assigns
                // ForgeEventFactory.onLivingChangeTargetMob(this, target).getNewTarget() when the event is not
                // cancelled, and does not call asValidTarget there. The value assigned is the event's new target, so a
                // @ModifyExpressionValue of the vanilla call (Puzzles Lib 26.3's change-target event) applies to it.
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/Mob",
                        "setTarget",
                        "Lnet/minecraft/world/entity/Mob;asValidTarget(Lnet/minecraft/world/entity/LivingEntity;)"
                                + "Lnet/minecraft/world/entity/LivingEntity;",
                        "Lnet/minecraftforge/event/entity/living/LivingChangeTargetEvent;getNewTarget()"
                                + "Lnet/minecraft/world/entity/LivingEntity;"),
                // Forge 26.3-66.1.0 only: RegistriesDatapackGenerator.forWorldLayer and
                // RegistryPatchGenerator.lambda$createWorldLookup$0 read RegistryDataLoader.WORLD_REGISTRIES in
                // vanilla;
                // Forge calls getWorldAndDimensionRegistries(), which returns WORLD_REGISTRIES followed by
                // DIMENSION_REGISTRIES. Fabric API's registry sync redirects both reads to its own world registries.
                new InjectionPointReplacement(
                        "net/minecraft/data/registries/RegistriesDatapackGenerator",
                        "forWorldLayer",
                        WORLD_REGISTRIES,
                        WORLD_AND_DIMENSION_REGISTRIES,
                        InjectionPointReplacement.Placement.FIELD_THEN_HOST_ENTRIES),
                new InjectionPointReplacement(
                        "net/minecraft/data/registries/RegistryPatchGenerator",
                        "lambda$createWorldLookup$0",
                        WORLD_REGISTRIES,
                        WORLD_AND_DIMENSION_REGISTRIES,
                        InjectionPointReplacement.Placement.FIELD_THEN_HOST_ENTRIES),
                // Forge 26.3-66.1.0: BlockLootSubProvider.run() iterates BuiltInRegistries.BLOCK.iterator() in vanilla
                // to check that every block with a loot table got one; Forge iterates getKnownBlocks().iterator()
                // (Iterable.iterator()), at the same place, whose default returns the blocks of the minecraft
                // namespace. Fabric API 26.3's data generation narrows the vanilla iterator to the minecraft namespace
                // for VanillaBlockLoot (@ModifyExpressionValue); on Forge's iterator it narrows the same blocks.
                new InjectionPointReplacement(
                        "net/minecraft/data/loot/BlockLootSubProvider",
                        "run",
                        "Lnet/minecraft/core/DefaultedRegistry;iterator()Ljava/util/Iterator;",
                        "Ljava/lang/Iterable;iterator()Ljava/util/Iterator;"),
                // Forge 26.3-66.1.0: EntityLootSubProvider.run() streams the entity types' holders with
                // LootTableSubProvider$Context.listContextElements(Registries.ENTITY_TYPE) in vanilla; Forge streams
                // getKnownEntityTypes() (all entity types, also for VanillaEntityLoot) mapped back to their holders
                // with EntityType.builtInRegistryHolder (Stream.map), at the same place, before the same forEach.
                // Fabric API 26.3's data generation filters that stream of holders to the minecraft namespace for
                // VanillaEntityLoot (@ModifyExpressionValue).
                new InjectionPointReplacement(
                        "net/minecraft/data/loot/EntityLootSubProvider",
                        "run",
                        "Lnet/minecraft/data/loot/LootTableSubProvider$Context;listContextElements("
                                + "Lnet/minecraft/resources/ResourceKey;)Ljava/util/stream/Stream;",
                        "Ljava/util/stream/Stream;map(Ljava/util/function/Function;)Ljava/util/stream/Stream;"),
                // CropBlock.getGrowthSpeed (Forge 26.2-65.1.5, 26.3-66.1.0): vanilla reads the soil's
                // FarmlandBlock.MOISTURE with BlockState.getValueOrElse(MOISTURE, 0) and counts it as watered when
                // above 0; Forge asks BlockState.isFertile(BlockGetter, BlockPos) at the same place, whose default
                // (IForgeBlock.isFertile) is exactly "farmland with MOISTURE above 0". Balm's crop mixin wraps the
                // vanilla read to count its custom farm blocks as watered (HostFertileSoil).
                new InjectionPointReplacement(
                        "net/minecraft/world/level/block/CropBlock",
                        "getGrowthSpeed",
                        "Lnet/minecraft/world/level/block/state/BlockState;getValueOrElse("
                                + "Lnet/minecraft/world/level/block/state/properties/Property;Ljava/lang/Comparable;)"
                                + "Ljava/lang/Comparable;",
                        "Lnet/minecraft/world/level/block/state/BlockState;isFertile("
                                + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"),
                // AbstractFurnaceBlockEntity.serverTick (Minecraft 1.21.1, Forge 52.1.21; javap): vanilla puts the
                // fuel's remainder back into the fuel slot with one NonNullList.set(int, Object) call (offset 255).
                // Forge's patch has the call twice, once when the item has a crafting remainder (offset 216) and once
                // when the stack was used up (offset 259), both with ItemStack.getCraftingRemainingItem(). Fabric API
                // 0.116's fabric-item-api-v1 AbstractFurnaceBlockEntityMixin captures
                // FabricItemStack.getRecipeRemainder
                // before the fuel is used (getStackRemainder, a ThreadLocal) and puts it into that one call
                // (setStackRemainder, @ModifyArg with allow = 1); two matches fail the check, so the module did not
                // load. FabricItem.getRecipeRemainder(ItemStack) is by default the item's vanilla remainder, which is
                // also what Forge's call returns for an item that does not override it; the two handlers are taken
                // out together so that the ThreadLocal is not left set. A mod that overrides FabricItem's
                // getRecipeRemainder for a fuel item gets Forge's remainder (getCraftingRemainingItem) there instead.
                new InjectionPointReplacement(
                        "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity",
                        "serverTick",
                        "net/fabricmc/fabric/mixin/item/AbstractFurnaceBlockEntityMixin#getStackRemainder",
                        "ItemStack.getCraftingRemainingItem() in both of its NonNullList.set calls",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                new InjectionPointReplacement(
                        "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity",
                        "serverTick",
                        "net/fabricmc/fabric/mixin/item/AbstractFurnaceBlockEntityMixin#setStackRemainder",
                        "ItemStack.getCraftingRemainingItem() in both of its NonNullList.set calls",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                // Entity.changeDimension(ServerLevel) (Minecraft 1.20.4, Forge 49.2.13; javap): vanilla reads Level.END
                // in changeDimension itself, after the new entity is created, to build the obsidian platform when the
                // destination is the End. Forge 49 passes the rest of changeDimension to its teleporter as a lambda,
                // lambda$changeDimension$16(ServerLevel, PortalInfo, Boolean), which holds that read
                // (findDimensionEntryPoint
                // has two more, which vanilla had too). fabric-dimensions-v1's EntityMixin redirects the read in
                // changeDimension (stopEndSpecificBehavior, no parameters) so that an entity with a custom teleport
                // target gets no platform; the body that took it over is the lambda.
                new InjectionPointReplacement(
                        "net/minecraft/world/entity/Entity",
                        "changeDimension",
                        "Lnet/minecraft/world/level/Level;END:Lnet/minecraft/resources/ResourceKey;",
                        "lambda$changeDimension$16(Lnet/minecraft/server/level/ServerLevel;"
                                + "Lnet/minecraft/world/level/portal/PortalInfo;Ljava/lang/Boolean;)"
                                + "Lnet/minecraft/world/entity/Entity;",
                        InjectionPointReplacement.Placement.IN_METHOD),
                // ServerPlayerGameMode.destroyBlock (Minecraft 1.21.1, Forge 52.1.21; javap): after the same checks
                // (getBlockEntity, getBlock, the game master block check, ForgeHooks.onBlockBreakEvent,
                // ItemStack.onBlockStartBreak, ServerPlayer.blockActionRestricted) vanilla calls
                // Block.playerWillDestroy; Forge calls ServerPlayerGameMode.isCreative() there once (offset 125; its
                // own removeBlock(BlockPos, Z) makes the playerWillDestroy call inside BlockState.onDestroyedByPlayer).
                // The locals (blockEntity, block, state) are vanilla's. Fabric API's PlayerBlockBreakEvents.BEFORE
                // (cancellable) runs there, as for the Forge builds above, which have preventsBlockDrops() instead.
                new InjectionPointReplacement(
                        GAME_MODE,
                        "destroyBlock",
                        "Lnet/minecraft/world/level/block/Block;playerWillDestroy(Lnet/minecraft/world/level/Level;"
                                + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
                                + "Lnet/minecraft/world/entity/player/Player;)"
                                + "Lnet/minecraft/world/level/block/state/BlockState;",
                        "L" + GAME_MODE + ";isCreative()Z"),
                // Recipe.getRemainingItems (Minecraft 1.21.1, Forge 52.1.21; javap): vanilla asks the ingredient's
                // Item.hasCraftingRemainingItem() and sets new ItemStack(Item.getCraftingRemainingItem()). Forge asks
                // the stack (ItemStack.hasCraftingRemainingItem, getCraftingRemainingItem) and sets that remainder
                // with its one NonNullList.set call. fabric-item-api-v1's RecipeMixin captures
                // FabricItemStack.getRecipeRemainder at RecipeInput.getItem (captureStack, a ThreadLocal) and
                // redirects the three calls (hasStackRemainder and replaceGetRecipeRemainder, 0 targets, and
                // getStackRemainder at NonNullList.set, which still matches). All four are taken out: left in, the
                // set redirect would put Fabric's remainder, which knows nothing of a Forge item's stack-sensitive
                // getCraftingRemainingItem, in place of Forge's. A mod that overrides FabricItem's
                // getRecipeRemainder gets Forge's remainder there instead.
                new InjectionPointReplacement(
                        "net/minecraft/world/item/crafting/Recipe",
                        "getRemainingItems",
                        "net/fabricmc/fabric/mixin/item/RecipeMixin#captureStack",
                        "ItemStack.hasCraftingRemainingItem() and getCraftingRemainingItem() on the input stack",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                new InjectionPointReplacement(
                        "net/minecraft/world/item/crafting/Recipe",
                        "getRemainingItems",
                        "net/fabricmc/fabric/mixin/item/RecipeMixin#hasStackRemainder",
                        "ItemStack.hasCraftingRemainingItem() and getCraftingRemainingItem() on the input stack",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                new InjectionPointReplacement(
                        "net/minecraft/world/item/crafting/Recipe",
                        "getRemainingItems",
                        "net/fabricmc/fabric/mixin/item/RecipeMixin#replaceGetRecipeRemainder",
                        "ItemStack.hasCraftingRemainingItem() and getCraftingRemainingItem() on the input stack",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                new InjectionPointReplacement(
                        "net/minecraft/world/item/crafting/Recipe",
                        "getRemainingItems",
                        "net/fabricmc/fabric/mixin/item/RecipeMixin#getStackRemainder",
                        "ItemStack.hasCraftingRemainingItem() and getCraftingRemainingItem() on the input stack",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                // Ingredient.<clinit> (Minecraft 1.21.1, Forge 52.1.21; javap): vanilla builds CONTENTS_STREAM_CODEC
                // with StreamCodec.map(...); Forge puts ForgeHooks.ingredientStreamCodec() there, which sends an
                // ingredient by its serializer (the item list for any ingredient that is not one of Forge's types).
                // fabric-recipe-api-v1's IngredientMixin wraps the result of that StreamCodec.map call with its
                // CustomIngredientPacketCodec (useCustomIngredientPacketCodec, @ModifyExpressionValue, 0 targets),
                // which
                // sends a custom ingredient as its serializer id and data. Taken out: next to Forge a Fabric custom
                // ingredient reaches the client as the stacks it matches (as a client without Fabric API reads it), and
                // the client's recipe display is less exact; the server matches with the custom ingredient itself. The
                // module's codec change (injectCodec, reading and writing JSON) still applies.
                new InjectionPointReplacement(
                        "net/minecraft/world/item/crafting/Ingredient",
                        "<clinit>",
                        "net/fabricmc/fabric/mixin/recipe/ingredient/IngredientMixin#useCustomIngredientPacketCodec",
                        "ForgeHooks.ingredientStreamCodec(), which sends an ingredient as its serializer or stacks",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                // LevelChunk.getBlockEntity(BlockPos, EntityCreationType) (Minecraft 1.21.1, Forge 52.1.21; javap):
                // vanilla drops a block entity that was marked removed with a Map.remove call after the
                // createBlockEntity branch (offset 96). Forge asks isRemoved() first and removes it before that
                // branch (offset 30); the second Map.remove (offset 47) takes the pending block entity.
                // fabric-lifecycle-events-v1's server WorldChunkMixin redirects the Map.remove call that follows
                // createBlockEntity (a slice, 0 targets) to fire BLOCK_ENTITY_UNLOAD for that block entity. The
                // redirect is taken out. Differs: no unload event for a block entity that was marked removed but was
                // still in the map when asked for; the event for the normal removal (removeBlockEntity, the
                // @Inject of the same mixin at markRemoved) still runs.
                new InjectionPointReplacement(
                        "net/minecraft/world/level/chunk/LevelChunk",
                        "getBlockEntity",
                        "net/fabricmc/fabric/mixin/event/lifecycle/server/WorldChunkMixin"
                                + "#onRemoveBlockEntity(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;",
                        "its own Map.remove of a block entity that isRemoved(), before the createBlockEntity branch",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                // LevelChunk.setBlockEntity(BlockEntity) (Minecraft 1.20.1, Forge 47.4.28; javap -l): Forge's patch
                // wraps
                // the body in one if (hasBlockEntity), so the local holding the block entity that Map.put replaced
                // (slot 3) is in scope from the put to the setRemoved() call and not at the return right after it.
                // fabric-lifecycle-events-v1's WorldChunkMixin (server and client) captures that local right after
                // setRemoved() (shift AFTER, CAPTURE_FAILEXCEPTION) to fire BLOCK_ENTITY_UNLOAD for it; Mixin found it
                // missing there and put in a handler that throws InjectionError when a block entity is replaced
                // (fabric-host-patch-probe, corpusFabricForge 1.20.1, 2026-11-12). The injection moves to right before
                // setRemoved(), where both captured locals are in scope. Differs: the unload event comes before the
                // replaced block entity is marked removed, as the same mixin's removeBlockEntity injection already
                // does.
                new InjectionPointReplacement(
                        "net/minecraft/world/level/chunk/LevelChunk",
                        "setBlockEntity",
                        "net/fabricmc/fabric/mixin/event/lifecycle/server/WorldChunkMixin#onRemoveBlockEntity"
                                + SET_BLOCK_ENTITY_UNLOAD_HANDLER,
                        "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V",
                        InjectionPointReplacement.Placement.BEFORE_CALL),
                new InjectionPointReplacement(
                        "net/minecraft/world/level/chunk/LevelChunk",
                        "setBlockEntity",
                        "net/fabricmc/fabric/mixin/event/lifecycle/client/WorldChunkMixin#onRemoveBlockEntity"
                                + SET_BLOCK_ENTITY_UNLOAD_HANDLER,
                        "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V",
                        InjectionPointReplacement.Placement.BEFORE_CALL),
                // BrewingStandBlockEntity.doBrew (Minecraft 1.21.1, Forge 52.1.21; javap): vanilla asks
                // Item.hasCraftingRemainingItem() of the ingredient and makes the remainder with new
                // ItemStack(Item.getCraftingRemainingItem()) after ItemStack.shrink(1). Forge asks the stack
                // (hasCraftingRemainingItem, getCraftingRemainingItem) before shrinking and drops or keeps that
                // remainder itself. fabric-item-api-v1's BrewingStandBlockEntityMixin captures
                // FabricItemStack.getRecipeRemainder at shrink (captureItemStack, a ThreadLocal), redirects the
                // Item.hasCraftingRemainingItem() call (hasStackRecipeRemainder, scanned 0 targets) and wraps the
                // new ItemStack(ItemLike) call (createStackRecipeRemainder); all three are taken out together so that
                // the ThreadLocal is not left set. The remainder is Forge's, as for the furnace above.
                new InjectionPointReplacement(
                        "net/minecraft/world/level/block/entity/BrewingStandBlockEntity",
                        "doBrew",
                        "net/fabricmc/fabric/mixin/item/BrewingStandBlockEntityMixin#captureItemStack",
                        "ItemStack.hasCraftingRemainingItem() and getCraftingRemainingItem() on the ingredient stack",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                new InjectionPointReplacement(
                        "net/minecraft/world/level/block/entity/BrewingStandBlockEntity",
                        "doBrew",
                        "net/fabricmc/fabric/mixin/item/BrewingStandBlockEntityMixin#hasStackRecipeRemainder",
                        "ItemStack.hasCraftingRemainingItem() and getCraftingRemainingItem() on the ingredient stack",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                new InjectionPointReplacement(
                        "net/minecraft/world/level/block/entity/BrewingStandBlockEntity",
                        "doBrew",
                        "net/fabricmc/fabric/mixin/item/BrewingStandBlockEntityMixin#createStackRecipeRemainder",
                        "ItemStack.hasCraftingRemainingItem() and getCraftingRemainingItem() on the ingredient stack",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                // AbstractFurnaceBlockEntity.isFuel and getBurnDuration (Minecraft 1.21.1, Forge 52.1.21; javap):
                // vanilla reads the fuel table with getFuel() in both; Forge asks ForgeHooks.getBurnTime(stack, null)
                // instead, which reads a table its ForgeHooks.updateBurns() filled from getFuel() (VANILLA_BURNS) after
                // the item's own getBurnTime and before the FurnaceFuelBurnTimeEvent. fabric-content-registries-v0
                // redirects the two getFuel() calls to FuelRegistryImpl.getFuelTimes() (canUseAsFuelRedirect,
                // getFuelTimeRedirect; scanned 0 targets) and also adds its entries to every result of getFuel()
                // (fuelTimeMapHook, @Inject at RETURN, which applies), so the fuel registry reaches Forge's table
                // through updateBurns(). Differs: a fuel added to Fabric's FuelRegistry after Forge last filled its
                // table is seen at the next updateBurns() (data pack reload), not at once.
                new InjectionPointReplacement(
                        "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity",
                        "isFuel",
                        "net/fabricmc/fabric/mixin/content/registry/AbstractFurnaceBlockEntityMixin#canUseAsFuelRedirect",
                        "ForgeHooks.getBurnTime(ItemStack, RecipeType), which reads the table built from getFuel()",
                        InjectionPointReplacement.Placement.HOST_COVERS),
                new InjectionPointReplacement(
                        "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity",
                        "getBurnDuration",
                        "net/fabricmc/fabric/mixin/content/registry/AbstractFurnaceBlockEntityMixin#getFuelTimeRedirect",
                        "ForgeHooks.getBurnTime(ItemStack, RecipeType), which reads the table built from getFuel()",
                        InjectionPointReplacement.Placement.HOST_COVERS));
    }
}
