[English](README.md) | [한국어](README.ko.md)

# plexus-game-v26_1

## What This Module Does
The game layer for Minecraft 26.1 (ADR-0010). It instantiates Native API declarations into actual game objects and provides hooks called by transformation rules.

## What It Owns
- `GameLayer26_1` (entry point), `LayerState`
- `HostPayloadHooks`: When custom payloads from the host loader and other loaders coexist on a single connection, writes the host's own payloads using the host codec (`plexus:custom_payload_encoding`), causes the host's channel checks to skip payloads from other loaders, and restores payloads from other loaders dropped by the host's bundle packet filtering back to their proper places (NeoForge host rules, ADR-0026 40·41).
- `BuiltInRegistryFreeze`: When freezing built-in registries a second time, skips tag binding for registries already frozen by another loader (`plexus:builtin_registries_refreeze`, ADR-0026 38).
- `GameHooks` (common hooks), `InteractionHooks` (interaction events and cancellable block events), `LifecycleHooks` (server, world, respawn, and entity lifecycle events), `client.ClientHooks` (client-only hooks)
- `content` (`PlexusBlock`, `PlexusItem`, `ContentInstaller`, `ModCreativeTabs`), `GameTags`, `client.CreativeTabPaging`, `commands`, `Networking`, `ModPacks`, `GeneratedDataPack`, `gametest`
- `Views`: Converts game objects into API views.
- `client.TitleScreenModCounts`: Renders loaded mod counts per loader (`ModCountLines`) at the bottom left of the title screen, above the version text. Text is looked up first in the game's current language (which resource packs can modify); if absent, it is looked up in Plexus Mod Loader's language files (`assets/plexus/lang/<language>.json` in `plexus-version-adapter-api`). When Forge renders that area, it only passes lines via `GameLayer26_1.brandingLines()`.

## What It Does Not Do
- Does not declare transformation rules. Rules reside in `Minecraft26_1Adapter` in `plexus-version-adapter-v26_1`.
- Does not validate declarations. Validation is performed by `plexus-runtime-impl`.

## Dependencies
- `plexus-version-adapter-api` (and the public API it exposes)
- slf4j
- Minecraft 26.1 client jar and libraries. `compileOnly` only, attached by the `plexus.minecraft-compile` plugin.

## Loading
- This jar is on the Plexus Mod Loader classpath, but classes are defined by the **game classloader** (`GameClassLoader.GAME_LAYER_PREFIX`).
- Code in the parent loader must not directly reference classes in this package.
- The `client` subpackage is only invoked from client-side rules and is therefore not loaded on dedicated servers.

## Build and Test
```powershell
.\gradlew.bat :plexus-game-v26_1:check   # BuiltInRulesTest: Applies built-in transformation rules to actual 26.1 classes on both sides (client and server) and verifies hook signatures
.\gradlew.bat :plexus-bootstrap:nativeApiGameTests :plexus-bootstrap:clientWorldReloadTest
```

## Differences from 26.2
Differs only where adapted for 26.1 after copying from `plexus-game-v26_2` (verified against the 26.1 jar using `javap`). The same code also runs 26.1.1 and 26.1.2 (the adapter's `gameVersions()`).
- HUD: 26.1 does not have `Hud`. The hook `ClientHooks.onHudRendered` receives `Gui`, and checks visibility via `Options.hideGui`.
- Opening screens: `Minecraft.setScreen(…)`/`Minecraft.screen` instead of `minecraft.gui.setScreen(…)`/`gui.screen()`.
- World painter: 26.1 does not have `LevelRenderer.submitFeatures`. The hook `onSubmitWorldFeatures` is called at the start of `submitEntities(PoseStack, LevelRenderState, SubmitNodeCollector)`. This is the first thing `submitFeatures` does in 26.2, called once per frame before `finalizeGizmoCollection`.
- Shader pipelines (`ModShaderPipelines`): Bind groups (`BindGroupLayout`, `BindGroupLayouts`) and `PrimitiveTopology` do not exist. Uniforms (`Globals`, `DynamicTransforms`, `Projection`, mod blocks) and samplers are declared individually using `withUniform`/`withSampler`, and vertex formats are specified using `withVertexFormat(…, VertexFormat.Mode.QUADS)`.
- Block entity types: The constructor and `BlockEntitySupplier` of `BlockEntityType` are private. Created using proxies and reflection like `PlexusMenus` (`ContentInstaller.newBlockEntityType`).
- Advancement triggers: `PlayerTrigger` is in `net.minecraft.advancements.criterion`.
- Movement controls: `FlyingMoveControl` and `SmoothSwimmingMoveControl` are not generic.
- Game tests: `GameTestHelper.makeMockServerPlayer(GameType)` does not exist. `GameTestContextView` directly handles what that method does in 26.2 (a survival `ServerPlayer` not added to the world).
- Native API extensions (event priorities and results, interaction events, cancellable block breaking and placing, server, world, respawn, and entity lifecycle events, player death hooks, tags, mod creative tab pages, item tooltips, `GameTestContext` helpers) use the same code as 26.2. Target methods for the 21 new rules were compared between the 26.1 and 26.2 jars using `javap -c`. Differences elsewhere are irrelevant to the rules.
  - `ServerPlayerGameMode.useItemOn`, `Player.interactOn`, `Player.attack`: Only the body differs; since these are start hooks, there is no impact.
  - `BlockItem.place`: Only the advancement trigger package differs (`advancements.criterion`; 26.2 uses `advancements.triggers`). Having 1 `placeBlock` call, 7 `areturn` instructions, and no writes to parameter slots are identical.
  - `CreativeModeInventoryScreen.init`: Opens the screen via `Minecraft.setScreen` (26.2 uses `Gui.setScreen`). Having 1 `return` is identical.
  - Creative tabs: As in 26.2, game tabs occupy all 14 slots (columns 0–6 in both top and bottom rows).

## Known Limitations
Client-side changes (HUD, world painter, shader pipelines, screens) have only been verified windowless up to the transformation plan and hook signatures. `clientWorldReloadTest`, which opens a window, was not run.

See "Constraints verified in experiments" in ADR-0010.
