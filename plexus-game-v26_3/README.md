[English](README.md) | [한국어](README.ko.md)

# plexus-game-v26_3

## What This Module Does
The game layer for Minecraft 26.3 (ADR-0010). It instantiates Native API declarations into actual game objects and provides hooks invoked by transformation rules.

## What It Owns
- `GameLayer26_3` (entry point), `LayerState`
- `HostPayloadHooks`: When custom payloads from the host loader and other loaders coexist on a single connection, writes the host's own payloads using the host codec (`plexus:custom_payload_encoding`), makes the host's channel checks skip payloads from other loaders, and restores payloads from other loaders dropped by the host's bundle packet filtering (NeoForge host rules, ADR-0026 40·41).
- `BuiltInRegistryFreeze`: When freezing built-in registries a second time, skips tag binding for registries already frozen by other loaders (`plexus:builtin_registries_refreeze`, ADR-0026 38).
- `GameHooks` (common hooks), `InteractionHooks` (interaction events and cancellable block events), `LifecycleHooks` (server, world, respawn, and entity lifecycle events), `client.ClientHooks` (client-only hooks)
- `content` (`PlexusBlock`, `PlexusItem`, `ContentInstaller`, `ModCreativeTabs`), `GameTags`, `client.CreativeTabPaging`, `commands`, `Networking`, `ModPacks`, `GeneratedDataPack`, `gametest`
- `Views`: Converts game objects into API views.
- `client.TitleScreenModCounts`: Renders the loaded mod count per loader (`ModCountLines`) in the bottom-left of the title screen, above the version text. Strings are looked up first in the game's current language (which resource packs can override), and if absent, in Plexus Mod Loader's language files (`assets/plexus/lang/<language>.json` in `plexus-version-adapter-api`). When Forge draws that area, it only passes lines via `GameLayer26_3.brandingLines()`.

## What It Does Not Do
- Does not declare transformation rules. Rules reside in `Minecraft26_3Adapter` in `plexus-version-adapter-v26_3`.
- Does not validate declarations. Validation is handled by `plexus-runtime-impl`.

## Dependencies
- `plexus-version-adapter-api` (and the public API it exposes)
- slf4j
- Minecraft 26.3 client JAR and libraries. `compileOnly` only, attached by the `plexus.minecraft-compile` plugin.

## Loading
- While this JAR is on the Plexus Mod Loader classpath, its classes are defined by the **game classloader** (`GameClassLoader.GAME_LAYER_PREFIX`).
- Code in the parent loader must not directly reference classes in this package.
- The `client` subpackage is called only from client-side rules, so it is not loaded on dedicated servers.

## Build and Test
```powershell
.\gradlew.bat :plexus-game-v26_3:check   # BuiltInRulesTest: Applies built-in transformation rules to actual 26.3 classes on both sides (client and server) and verifies hook signatures
.\gradlew.bat :plexus-bootstrap:nativeApiGameTests :plexus-bootstrap:clientWorldReloadTest
```

## Differences from 26.2

A supported version since 1.2.0 (`supported` in `gradle/minecraft-versions.properties`). Copied from `plexus-game-v26_2` and adapted for 26.3. The modified locations and reasons are documented in "Progress" in `docs/plans/MINECRAFT_26_3.md` (`docs/` is not in this public repository). In summary:

- Key bindings: `client.GameKeyCodes` converts Plexus Mod Loader key codes (GLFW values) to 26.3 SDL scancodes (ADR-0029).
- Mob spawns: Biome environmental attribute `NATURAL_MOB_SPAWNS` (`WorldgenModifier.applySpawns`, `Views`, `GeneratedDataPack`).
- World generation: `ChunkGenerator.buildTerrain`, `Feature` containing configuration (`FEATURE_TYPE`, `worldgen/feature`), compiled density functions (`worldgen.CodeWorldgen`).
- Rendering: `com.mojang.renderpearl.api` (`client.ModShaderPipelines`), uniform buffers looked up by pipeline position, post-processing effect is `spectatedEntityPostEffect`.
- Tests: `GameKeyCodesTest`, `ModPacksTest` (pack overlays), `ModShaderSourcesTest` (compiles example shaders without a window using the game's shaderc compiler).

## Known Limitations
See "Constraints Identified in Experiments" in ADR-0010.
