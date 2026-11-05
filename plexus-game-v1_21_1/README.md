[English](README.md) | [한국어](README.ko.md)

# plexus-game-v1_21_1

## What this module does
Implements the Plexus Mod Loader Native API for Minecraft 1.21.1 using remapped game classes. Loaded by the transforming game class loader (ADR-0010).

## What it owns
`dev.plexusmodloader.game.layer.v1_21_1.GameLayer1_21_1`, the static hook methods in `GameHooks`, `InteractionHooks`, `LifecycleHooks`, and the client-only layer in `client.*`:

- `ClientHooks`: key bindings, `ClientEvents.TICK`, `KEY_INPUT`, `MOUSE_BUTTON`, `MOUSE_SCROLL`, `FIELD_OF_VIEW`, `JOINED_WORLD` and `LEFT_WORLD`, HUD layers, mod menu screens, client and configuration payloads, `sendToServer`, particle providers, block entity and entity renderers, mod asset reload listeners, world painters, `ClientView` (screens, camera, post effects, leave world).
- `ItemTooltips` (`ClientEvents.ITEM_TOOLTIP`), `InputCodes`, `DynamicTextures`, `ModShaderPipelines` and `WorldPainting` (shader quads, lines, boxes, points, text).
- Screens and renderers: `PlexusScreen`, `PlexusContainerScreen`, `PlexusBlockEntityRenderer`, `PlexusModelRenderer`, `PlexusHumanoidRenderer`, `PlexusEntityModel`, `PlexusParticle`.
- `CreativeTabPaging`, `TitleScreenModCounts`, `NotLoadedModsScreen`.

## What it does not do
Does not expose Minecraft types in public Plexus Mod Loader APIs (`dev.plexusmodloader.api.*`). Does not support other Minecraft versions.

## Dependencies
`plexus-version-adapter-api`, `slf4j.api`, and the remapped Minecraft 1.21.1 client jar (`compileOnly`, provided by `plexus.minecraft-compile`).

## Key entry points
`GameLayer1_21_1`

## Build and test
```powershell
.\gradlew.bat :plexus-game-v1_21_1:check
```
Runs `BuiltInRulesTest`, which plans and applies every transformation rule of `Minecraft1_21_1Adapter` (client rules for the client, the rest for a dedicated server) to the remapped 1.21.1 classes and checks that each inserted call reaches a public static hook with the exact descriptor. The client tests cover input code conversion, ARGB to ABGR pixel conversion, the tooltip line view, std140 uniform layout, the generated shader JSON, and post effect file names.

## Flow of the client layer
`Minecraft1_21_1Adapter` inserts calls to `ClientHooks` at the game's own methods (`Minecraft.tick`, `KeyboardHandler.keyPress`, `MouseHandler.onPress` and `onScroll`, `Gui.render`, `LevelRenderer.renderLevel`, `GameRenderer.getFov`, `ClientPacketListener.handleCustomPayload`, `ParticleEngine.registerProviders`, `MenuScreens.create`, and others). The hooks read the declarations the runtime collected in `LayerState` and publish events through the context's event bus. A mod's HUD layer, world painter, or block entity painter that throws is disabled once with `PLEXUS-CLIENT-003`.

## Limits

These are the places where 1.21.1 cannot do what 1.21.11 does the same way. Each one fails with a logged code or behaves as described; none is silently skipped.

- **Shaders use the 1.21.1 shader dialect.** Mod shader sources must be `assets/<namespace>/shaders/core/<name>.vsh` and `.fsh` (`#version 150`), and the pipeline's shader location must start with `core/`, because 1.21.1's `ShaderInstance` loads core shaders only from that directory; another location fails with `PLEXUS-CLIENT-006` and the quads are skipped. The `#moj_import <minecraft:dynamictransforms.glsl>` blocks of 1.21.11 do not exist: declare `uniform mat4 ModelViewMat;`, `ProjMat`, `vec4 ColorModulator`, `float GameTime`, the fog uniforms or `vec2 ScreenSize` as plain uniforms, and Plexus Mod Loader declares those in the shader's JSON when the source mentions them. `ModUniforms` stays a `layout(std140) uniform ModUniforms { ... };` block; Plexus Mod Loader creates the uniform buffer with OpenGL and rejects the pipeline (`PLEXUS-CLIENT-006`) when the block is missing or its size differs from the std140 size of the declared fields. A mod that targets several versions needs a per-version asset set for its shaders.
- Shader pipelines are built on first use and not rebuilt on a resource reload (F3+T); restart the client after editing shader files.
- **Post effects** read the game's 1.21.1 chain format from `assets/<namespace>/shaders/post/<path>.json`, not 1.21.11's `post_effect/<path>.json`. A missing file makes `setPostEffect` return `false`. `EffectInstance` builds `shaders/program/<name>.json` in the `minecraft` namespace, so a chain names `"name": "<mod id>_<program>"` and the program files are `assets/minecraft/shaders/program/<mod id>_<program>.json` and `.fsh`.
- **Item models** come from `models/item/<name>.json`, because 1.21.1 has no item definitions (`items/<name>.json`, added in 1.21.4). `LaterFormatPack` answers that name from the definition; a model file under that name wins. A definition that chooses between models shows the first one it names; one that names no model fails with `PLEXUS-RESOURCE-011`, which the game logs when it loads models.
- **Worlds with a mod's dimension** open without a backup question: the game calls a world stable only with exactly the built-in dimensions (`WorldDimensions.bake` compares the count), and the rule `plexus:mod_dimension_count` (hook `GameHooks.onBakedDimensionCount`) asks whether the built-in ones are there, as 26.x does. A dimension from a data pack that is not a mod's stays experimental.
- **World painter points** are drawn as small cubes (half-edge `size * 0.0125` blocks), not as screen-space dots, because 1.21.1 has no gizmo collection. Lines use the game's line shader with the requested width; boxes are an outline plus a translucent fill; text is a billboard (`onTop` text is drawn see-through).
- **Key binding categories** are translation keys in 1.21.1: a mod's controls category is `key.category.<mod id>.keybinds`, the same key 1.21.11 derives, so one language file serves both.
- **`DynamicTexture` pixels** are converted from `0xAARRGGBB` to the game's `0xAABBGGRR` on upload.
- Not run in a game window: key, mouse and scroll events, HUD layers, mod screens and menus, block entity and mob renderers, particles, shader pipelines, world painters, dynamic textures, post effects, item tooltips, key bindings and camera control were checked by compiling against the real 1.21.1 classes and by `BuiltInRulesTest` applying every rule, not by looking at a rendered frame. `runClient`, `clientWorldReloadTest` and `launcherProfileTest` open windows and were not run for 1.21.1.
