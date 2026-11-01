[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-v26_1

## What this module does
Contains knowledge specific to Minecraft 26.1. Includes client/server entry points and dozens of built-in transformation rules (branding, title screen mod counts, registry and payload handling, Native API event hooks, etc.).

## What it owns
`dev.plexusmodloader.game.version.v26_1.Minecraft26_1Adapter` and `META-INF/services` registration

## What it does not do
Does not support other versions. Does not place Minecraft names outside this module.

## Dependencies
`plexus-version-adapter-api`. Has no compile dependency on the Minecraft jar. Names are strings verified with `javap`.

## Key entry points
`Minecraft26_1Adapter`

## Build and test
```powershell
.\gradlew.bat :plexus-bootstrap:serverBootSmokeTest
```

Verifies rule acceptance and application on actual 26.1. The client is verified with `:plexus-bootstrap:runClient`.

## Representative flows
`plexus:client_brand` (CLIENT) and `plexus:server_brand` (both sides) replace `"vanilla"` with `"plexus"`. The failure policy is DISABLE_RULE.

`plexus:client_title_mod_counts` (CLIENT, DISABLE_RULE) replaces the sole `GuiGraphicsExtractor.text(Font, String, int, int, int)` call where the title screen `TitleScreen.extractRenderState` draws the version text with `client.TitleScreenModCounts.onVersionText` in the game layer (`RedirectCall`, ADR-0013). The hook draws that line and draws the per-loader mod counts above it. Because MinecraftForge's patch lacks this call, this rule is disabled on Forge, and `plexus-bridge-forge`'s `plexus:client_title_mod_counts_forge` draws above Forge branding instead.

`plexus:builtin_registries_refreeze` (all sides, DISABLE_RULE, after `plexus:content_registration`) replaces the sole `bindBootstrappedTagsToEmpty(Registry)` call in `BuiltInRegistries.freeze()` with `BuiltInRegistryFreeze.bindBootstrappedTagsToEmpty` in the game layer (`RedirectCall`, ADR-0026 38). The hook binds tags like vanilla for unfrozen registries, and skips binding for registries that are already frozen. Fabric API refreezes built-in registries once more after mod initialization, because under NeoForge, NeoForge's `GameData.freezeData` has already bound and frozen them. In runs that freeze registries only once (vanilla, Fabric only, NeoForge only), the behavior is identical. Verification: 26.1 and 26.1.2 client jars and client jars patched by NeoForge 26.1.0.19-beta and 26.1.2.122 (`javap`).

`plexus:custom_payload_encoding` (all sides, DISABLE_RULE) replaces the sole `StreamCodec.encode` call in `CustomPacketPayload$1.writeCap` with `HostPayloadHooks.encode` in the game layer (`RedirectCall`, after Mixin phase, ADR-0026 40). Without a host loader, it is identical to the original call; if NeoForge is the host, it writes NeoForge's own payloads using the NeoForge codec.

## Differences from 26.2
Copied from `plexus-version-adapter-v26_2`. Two of the 67 rules have different targets (verified via `javap` against the 26.1 jar). The 21 rules added in the Native API extension (`plexus:block_break`, `plexus:use_block`, `plexus:use_item`, `plexus:use_entity`, `plexus:attack_entity`, `plexus:block_place`, `plexus:block_placed`, `plexus:player_death`, `plexus:world_loaded`, `plexus:world_saving`, `plexus:world_unloaded`, `plexus:server_stopped`, `plexus:player_respawned`, `plexus:entity_joined_world`, `plexus:creative_tab_pages`, `plexus:client_creative_tabs_clicked`/`_released`/`_hovered`/`_drawn`, `plexus:client_item_tooltip`, `plexus:client_creative_page_buttons`) have the same targets, descriptors, and preconditions as 26.2 (`plexus-game-v26_1` README).
- `plexus:client_hud`: `net/minecraft/client/gui/Gui.extractRenderState` instead of `net/minecraft/client/gui/Hud` (same descriptor).
- `plexus:client_world_painters`: `LevelRenderer.submitEntities(PoseStack, LevelRenderState, SubmitNodeCollector)` instead of `LevelRenderer.submitFeatures(LevelRenderState, SubmitNodeCollector, boolean)`.

## Hotfixes
`gameVersions()` returns `26.1`, `26.1.1`, and `26.1.2` (ADR-0011 item 4, `hotfixes.26.1` in `gradle/minecraft-versions.properties`). The three client jars were compared class by class.
- 26.1 → 26.1.1: `SharedConstants` (world and snapshot protocol version constants), `DetectedVersion`, `PlayerEntry` (social screen)
- 26.1.1 → 26.1.2: the above two classes, `Checkbox`, `AbstractReportScreen`, `ServerGamePacketListenerImpl.handleAttack`

Rule targets and members used by the game layer did not change (`ServerGamePacketListenerImpl.handleCustomPayload` is identical). The target classes of the 21 new rules (`ServerPlayerGameMode`, `Player`, `BlockItem`, `ServerPlayer`, `MinecraftServer`, `ServerLevel`, `ServerLevel$EntityCallbacks`, `PlayerList`, `CreativeModeTabs`, `CreativeModeInventoryScreen`, `ItemStack`) and `AbstractContainerScreen`, `Screen`, and `GameTestHelper` read reflectively by the game layer are also identical across the client jars and server jars of all three versions according to `javap -c` (excluding constant pool indices). The game layer does not inline version constants at compile time, but reads them at runtime. At runtime, the runtime plans this adapter's rules for the running version (`AdapterTransformRules`).

## Known limitations
Target jar SHA-1s are client `19177183…` (26.1), `377031a9…` (26.1.1), and `4e618f09…` (26.1.2). Client-only rules were verified only up to transformation planning, preconditions, bytecode verification, and hook signatures without a window.
