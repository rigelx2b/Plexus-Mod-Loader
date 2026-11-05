[English](README.md) | [한국어](README.ko.md)

# plexus-game-v1_20_4

## What this module does
Implements the Plexus Mod Loader Native API for Minecraft 1.20.4 on the game classes remapped to Mojang names (ADR-0019, ADR-0035). The common (server) half runs in the game and is verified: mod content, commands, networking, world services, generated data, tags, attributes, entities, world generation, world backups and mod game tests. The client half is implemented and compiles, and its rules apply to the real 1.20.4 classes, but nothing that needs a window has run yet (see Limits). Loaded by the transforming game class loader (ADR-0010).

## What it owns
- `GameLayer1_20_4` (entry point), `LayerState`
- `GameHooks` (common hooks), `InteractionHooks` (interaction events and cancellable block events), `LifecycleHooks` (server, world, respawn and entity life cycle events)
- The client half, in `client`: `ClientHooks` (key bindings, input and tick events, FOV, join and leave, HUD, client and configuration payloads, menu screens, particle, block entity and entity renderers, asset reload, world painters, post effects, `ClientView`), `InputCodes`, `ItemTooltips`, `CreativeTabPaging`, `TitleScreenModCounts`, `NotLoadedModsScreen`, `DynamicTextures`, `PlexusScreen`, `PlexusContainerScreen`, `PlexusParticle`, `PlexusBlockEntityRenderer`, `PlexusEntityModel`, `PlexusModelRenderer`, `PlexusHumanoidRenderer`, `ModShaderPipelines`, `WorldPainting`
- `content` (`PlexusBlock`, `PlexusItem`, `ContentInstaller`, `ModCreativeTabs`, `ItemComponents`, `PlexusMenu`), `entity` (`EntityInstaller`, `PlexusMob`, `PlexusProjectile`, `PlexusMobEffect`), `commands`, `worldgen` (`CodeWorldgen`, `PlexusChunkGenerator`), `gametest` (`GameTestMain`, `GameTests`, `GameTestContextView`)
- `Views` (game objects as API views), `WorldServices` (world storage and scheduling), `Networking`, `SyncChannel`, `ConfigurationExchanges`, `EntityData`, `GameTags`, `GeneratedDataPack`, `ModPacks`, `ModReloadListener`, `WorldBackups`, `WorldgenModifier`, `PermissionChecks`

## What it does not do
- Does not declare transformation rules. They live in `Minecraft1_20_4Adapter` in `plexus-version-adapter-v1_20_4`.
- Does not validate declarations. `plexus-runtime-impl` does.
- Does not expose Minecraft types in the public API (`dev.plexusmodloader.api.*`) or support other Minecraft versions.

## Dependencies
`plexus-version-adapter-api`, `slf4j.api`, and the remapped Minecraft 1.20.4 client jar (`compileOnly`, provided by `plexus.minecraft-compile`).

## Key entry points
`GameLayer1_20_4`; the game test server starts at `gametest.GameTestMain` because the 1.20.4 server jar has no `net.minecraft.gametest.Main`.

## Build and test
```powershell
.\gradlew.bat :plexus-game-v1_20_4:check "-PplexusMinecraft=1.20.4"
.\gradlew.bat :plexus-bootstrap:gameTests "-PplexusTests=*:*" "-PplexusMinecraft=1.20.4"
```
`BuiltInRulesTest` applies every rule of `Minecraft1_20_4Adapter` to the remapped 1.20.4 classes and finds each inserted call's hook. `NetworkingTest` (payload bytes of a declared channel), `WorldStoragePersistenceTest` (world storage written to and read from the game's `DimensionDataStorage`), `ItemComponentsTest` (components on a real `ItemStack`, saved and loaded), `GeneratedDataPackTest`, `OperationsTest`, `ClientLogicTest` (input values, shader program files, paths, tooltips) and `PlexusEntityModelTest` (baking a mob model and its keyframe animations) check the layer without a window. `gameTests` runs the mods' in-game tests on Mojang's game test server.

## How 1.20.4 differs from the 1.21.1 layer
- **Item components** are tags of the stack's NBT under `plexus_components` (1.20.4 has no data components); they travel with the stack and are saved with it.
- **Payloads** are a channel ID plus bytes, decoded in the private `readPayload` of the two custom payload packets (hook `GameHooks.onReadPayload`), where 1.21.1 has stream codecs. The play and the configuration listener share one `handleCustomPayload`.
- **Attribute modifiers** are identified by UUID; a modifier ID maps to a name-based UUID.
- **Block** callbacks are the public `use`, `getShape`, `randomTick`, ... of 1.20.4; a mod's `onUse` runs for the main hand only, as the game asks a block to use a click without an item.
- **Block entities** save with `saveAdditional(CompoundTag)` and `load(CompoundTag)`; a mod block entity with an inventory extends `RandomizableContainerBlockEntity`.
- **Data pack folders** are plural: `tags/blocks`, `tags/items`, `tags/entity_types`, `structures`.
- **Game rules** have no range in 1.20.4; a declared minimum and maximum is enforced when the value changes.
- **Dimension change** of a non-player entity creates it anew in the target level, as `Entity.teleportTo(ServerLevel, ...)` does.

## How the client half differs from 1.21.x
- **Rendering is immediate.** 1.20.4 draws block entities and entities with `MultiBufferSource`; there are no render states or `SubmitNodeCollector`. Block entity painters draw while the game renders the block entity, mob models extend `HierarchicalModel<PlexusMob>` and read the mob's `AnimationState`s, and the world painter draws at the end of `LevelRenderer.renderLevel`.
- **World painter shapes** are built here, since 1.20.4 has no gizmos: lines in a render type per width (with and without the depth test), boxes as line boxes and filled boxes, text as a billboard like a name tag (a scale of 1 is the name tag's 0.025), and a point as a small cube (1/32 block per pixel of its size).
- **Shader pipelines** use the game's core shaders. 1.20.4 has no render pipelines and no uniform blocks for mods: `ModShaderPipelines` writes the program file the game expects (`shaders/core/<name>.json`) from the `ShaderPipelineDefinition`, so a mod ships only its `.vsh` and `.fsh`, in the GLSL 1.50 core dialect: `uniform mat4 ModelViewMat`, `ProjMat`, `uniform vec4 ColorModulator`, `uniform float GameTime`, `uniform sampler2D Sampler0..`, and each `ShaderUniform` as a plain `uniform float/vec2/vec3/vec4` of its own name (not a `ModUniforms` block). The 26.x and 1.21.11 shader sources do not compile here; a mod ships the 1.20.4 ones in a resource pack overlay.
- **Post effects** are chains in `assets/<namespace>/shaders/post/<path>.json` with programs in `shaders/program/`, where later versions read `post_effect/<path>.json`; `ClientView.setPostEffect` takes the same ID and reads the 1.20.4 file.
- **Key bindings** use a category named by the translation key `key.categories.<mod id>`.
- **Menus and particles**: the mod menu screen is opened by a rule on `MenuScreens.create`; particle providers are registered through the private `ParticleEngine.register` with a proxy of its package-private factory interface.

## Limits

- **Nothing that needs a window has been run.** The client rules apply to the real 1.20.4 classes and pass `BuiltInRulesTest`, and the logic that needs no window is tested (`ClientLogicTest`, `PlexusEntityModelTest`), but no client was started: HUD, screens, key bindings, tooltips, particles, renderers, shaders, post effects, world painters and payload delivery to a client are **not yet verified**.
- **Shader dialect.** A mod's shader sources written for 26.x or 1.21.11 (`#moj_import` or `#include` of uniform blocks, a `ModUniforms` block, `layout(location = ...)`) do not compile on 1.20.4 and the pipeline is reported with `PLEXUS-CLIENT-006`; see "Shader pipelines" above for the dialect. The shader itself cannot be compiled without a GL context, so a mod's 1.20.4 shaders are unchecked until a window is opened.
- **Approximations in the world painter.** Line width is the width of the line type's `lineWidth` (the game's line shader), a point is a cube, and text size is the name tag's size times the scale; the drawing differs in detail from 26.x gizmos.
- **Mod uniforms** are set when the render type is set up, so a value read at that moment is used for every quad of the batch. A `ShaderUniform` name must be a valid GLSL identifier (the API already requires it) and must not be one the program declares itself (`ModelViewMat`, `ProjMat`, `ColorModulator`, `GameTime`).
- **Textures per pipeline**: at most the four of `ShaderPipelineDefinition.MAX_TEXTURES`, bound as `Sampler0..3`.
- **Networking is verified at the byte level** (`NetworkingTest`), not with a connecting client; configuration exchanges (`ConfigurationExchanges`, `ClientHooks.onConfigurationPayload`) run only when a client connects, and no game test covers them.
- **Later pack layout.** `LaterFormatPack` shows a mod jar's pack in the names 1.20.4 reads: a data folder (`tags/blocks`, `loot_tables`, `recipes`, `advancements`, `structures`, ...) is answered from the later singular folder, and `models/item/<name>.json` from the item definition `items/<name>.json`. A file under the older name, in the jar or an overlay, wins. An item definition that chooses between models shows the first one it names; one that names no model fails with `PLEXUS-RESOURCE-011`, which the game logs when it loads models.
- Only the folder changes, not the file. Data and shaders in later file formats still need 1.20.4 copies; a mod can ship them in a `pack.mcmeta` overlay whose entries also carry `formats` (the 1.20.4 key). Data packs are format 26 and resource packs format 22.
- **Post effect programs** are read from the `minecraft` namespace, because `EffectInstance` builds `shaders/program/<name>.json` there: a chain names `"name": "<mod id>_<program>"` and the program files are `assets/minecraft/shaders/program/<mod id>_<program>.json` and `.fsh`.
- **Worlds with a mod's dimension.** The game calls a world stable only with exactly the built-in dimensions (`WorldDimensions.bake` compares the count), and asks for a backup before opening any other world. The rule `plexus:mod_dimension_count` (hook `GameHooks.onBakedDimensionCount`) asks whether the built-in ones are there, as 26.x does. Mod packs count as built in (`LaterFormatPack.isBuiltin`): the game registers an entry of a pack that is not built in as experimental, and 1.20.4 has no known-pack information. A dimension from a data pack that is not a mod's stays experimental.
- Not ported because 1.20.4 has no equivalent: the 26.x early window title and loading overlay (`PlexusWindowTitle`, `PlexusLoadingOverlay`), which exist for 26.3 only.