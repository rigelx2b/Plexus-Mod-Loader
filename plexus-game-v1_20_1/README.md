[English](README.md) | [한국어](README.ko.md)

# plexus-game-v1_20_1

## What this module does
Implements the Plexus Mod Loader Native API for Minecraft 1.20.1 using remapped game classes. Loaded by the transforming game class loader (ADR-0010, ADR-0019, ADR-0035).

## What it owns
`dev.plexusmodloader.game.layer.v1_20_1.GameLayer1_20_1`, the static hook methods in `GameHooks`, `InteractionHooks`, `LifecycleHooks`, and client hooks in `client.ClientHooks`, `client.CreativeTabPaging`, `client.TitleScreenModCounts`, `client.ItemTooltips`, `client.PlexusParticle`, `client.PlexusBlockEntityRenderer`, `client.PlexusScreen`, `client.PlexusContainerScreen`, and `client.PlexusHumanoidRenderer`.
Also owns content, entity, command, worldgen, gametest, and networking layer adapters for 1.20.1.

## What it does not do
Does not expose Minecraft types in public Plexus Mod Loader APIs (`dev.plexusmodloader.api.*`). Does not support other Minecraft versions.

## Dependencies
`plexus-version-adapter-api`, `slf4j.api`, and the remapped Minecraft 1.20.1 client jar (`compileOnly`, provided by `plexus.minecraft-compile`).

## Key entry points
`GameLayer1_20_1`

## Build and test
```powershell
.\gradlew.bat :plexus-game-v1_20_1:test
```
Runs `BuiltInRulesTest`, verifying that every built-in transformation rule (server/common and client) defined in `Minecraft1_20_1Adapter` apply cleanly to the remapped 1.20.1 classes and find their corresponding public static hooks.

## Limits and Version Characteristics

- **Supported Version (ADR-0035):** Fully implements common/server and client Native API contracts for 1.20.1.
- **No Configuration Phase:** Minecraft 1.20.1 predates the Configuration network phase (added in 1.20.2). A mod's configuration exchange runs as a login query instead (`ConfigurationExchanges`): the login is held while each exchange sends its request and waits for the answer or the timeout, then the player goes on or the connection is refused. A client that does not know the channel answers with no data, which counts as no answer. The game ends a login after 600 ticks, so a mod's timeouts together must stay under that.
- **No Render Pipelines:** Minecraft 1.20.1 has no render pipelines. A mod's shader pipeline is built as a core shader program (`ModShaderPipelines`): the program file is written from the `ShaderPipelineDefinition`, the mod ships only its `.vsh` and `.fsh` in the GLSL 1.50 core dialect, and mod uniforms are declared by name instead of a `ModUniforms` block.
- **Later Pack Layout:** `LaterFormatPack` shows a mod jar's pack in the names 1.20.1 reads. A data folder (`tags/blocks`, `loot_tables`, `recipes`, `advancements`, `structures`, ...) is answered from the later singular folder, and `models/item/<name>.json` from the item definition `items/<name>.json`. A file under the older name, in the jar or an overlay, wins. Only the folder changes, not the file: a recipe in a later format still fails to parse and needs a copy in an overlay. An item definition that chooses between models shows the first one it names; one that names no model fails with `PLEXUS-RESOURCE-011`, which the game logs when it loads models.
- **Post Effect Programs:** `EffectInstance` builds `shaders/program/<name>.json` in the `minecraft` namespace, so a chain names `"name": "<mod id>_<program>"` and the program files are `assets/minecraft/shaders/program/<mod id>_<program>.json` and `.fsh`. The chain itself stays at `assets/<namespace>/shaders/post/<path>.json`, which `ClientView.setPostEffect` reads.
- **Worlds With a Mod's Dimension:** The game calls a world stable only with exactly the built-in dimensions (`WorldDimensions.bake` compares the count), and asks for a backup before opening any other world. The rule `plexus:mod_dimension_count` (hook `GameHooks.onBakedDimensionCount`) asks whether the built-in ones are there, as 26.x does. Mod packs count as built in (`LaterFormatPack.isBuiltin`): the game registers an entry of a pack that is not built in as experimental, and 1.20.1 has no known-pack information. A dimension from a data pack that is not a mod's stays experimental.
- **GameTest Runner Entry Point:** 1.20.1 lacks `net.minecraft.gametest.Main`; headless GameTest runs start at `dev.plexusmodloader.game.layer.v1_20_1.gametest.GameTestMain`.
