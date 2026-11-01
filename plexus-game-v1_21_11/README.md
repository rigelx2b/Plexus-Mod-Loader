[English](README.md) | [한국어](README.ko.md)

# plexus-game-v1_21_11

## What This Module Does
This is the game layer for Minecraft 1.21.11 (ADR-0010). It instantiates Native API declarations into actual game objects and provides hooks called by transformation rules.

## What It Owns
- `GameLayer1_21_11` (entry point), `LayerState`
- `GameHooks` (common hooks), `InteractionHooks` (interaction events and cancellable block events), `LifecycleHooks` (server, world, respawn, and entity lifecycle events), `client.ClientHooks` (client-only hooks)
- `content` (`PlexusBlock`, `PlexusItem`, `ContentInstaller`, `ModCreativeTabs`), `GameTags`, `client.CreativeTabPaging`, `commands`, `Networking`, `ModPacks`, `GeneratedDataPack`, `gametest`
- `Views`: Converts game objects into API views.
- `client.TitleScreenModCounts`: Renders the number of mods loaded per loader (`ModCountLines`) in the bottom left of the title screen, above the version text. Text is looked up first in the game's current language (which resource packs can override); if not found, it is looked up in Plexus Mod Loader's language file (`assets/plexus/lang/<language>.json` in `plexus-version-adapter-api`). When Forge renders that area, it only passes the lines via `GameLayer1_21_11.brandingLines()`.

## What It Does Not Do
- Does not declare transformation rules. The rules reside in `Minecraft1_21_11Adapter` in `plexus-version-adapter-v1_21_11`.
- Does not validate declarations. Validation is handled by `plexus-runtime-impl`.

## Dependencies
- `plexus-version-adapter-api` (and the public APIs it exposes)
- slf4j
- The Minecraft 1.21.11 client jar remapped to Mojang names (ADR-0019, present only in the Gradle cache) and libraries. Attached as `compileOnly` only, by the `plexus.minecraft-compile` plugin.

## Loading
- This jar is on the Plexus Mod Loader classpath, but its classes are defined by the **game classloader** (`GameClassLoader.GAME_LAYER_PREFIX`).
- Code in the parent loader must not directly reference classes in this package.
- Because the `client` subpackage is only called from client-side rules, it is not loaded on dedicated servers.

## Build and Test
```powershell
.\gradlew.bat :plexus-game-v1_21_11:check   # BuiltInRulesTest: Applies built-in transformation rules to actual 1.21.11 classes on both sides (client and server) and verifies hook signatures
.\gradlew.bat :plexus-bootstrap:nativeApiGameTests :plexus-bootstrap:clientWorldReloadTest
```

## Differences from 26.1

**This is an experimental version** (ADR-0019, `experimental` in `gradle/minecraft-versions.properties`). Because it is not a supported version, it is not included in the installer or SDK.

Copied from `plexus-game-v26_1` using `newMinecraftVersion -Pexperimental=true` and then adapted for 1.21.11. The remapped jar was verified with `javap`. The changes are in "1.21.11 Server PoC" in `docs/plans/MINECRAFT_1_21.md` (`docs/` is not in this public repository). A summary follows:

- GUI uses `GuiGraphics` (`drawString`, `render`, container screen `renderBg`).
- Render state is in the `net.minecraft.client.renderer.state` package.
- Pipelines are created with `withDepthTestFunction`, `withDepthWrite`, and `withBlend`, and blocks are rendered with `SubmitNodeCollector.submitBlock`.
- The world storage file name is `data/plexus_<namespace>__<path>.dat` (in 1.21.11, `SavedDataType` takes a single file name instead of an identifier).
- `Player.interactOn` does not have a click position. Messages are sent via `displayClientMessage`.
- Most occurrences of "1.21.11" in code comments were simply replaced from 26.1 during copying. Facts verified against 1.21.11 are based on the document above and `BuiltInRulesTest`.

## Known Limitations
Client-side changes (HUD, world painter, shader pipelines, screens) have only been verified windowless up to transformation plans and hook signatures. `clientWorldReloadTest`, which opens a window, was not run.

See "Constraints Confirmed in Experiments" in ADR-0010.
