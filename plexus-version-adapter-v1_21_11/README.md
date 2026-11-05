[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-v1_21_11

## What this module does
Contains Minecraft 1.21.11-specific knowledge. It includes client/server entry points and dozens of built-in transformation rules (branding, title screen mod counts, registry and payload handling, Native API event hooks, etc.).

## What it owns
`dev.plexusmodloader.game.version.v1_21_11.Minecraft1_21_11Adapter` and the `META-INF/services` registration

## What it does not do
Does not support other versions. Does not place Minecraft names outside this module.

## Dependencies
`plexus-version-adapter-api`. Has no compile dependency on the Minecraft jar. Names are strings verified with `javap`.

## Key entry points
`Minecraft1_21_11Adapter`

## Build and test
```powershell
.\gradlew.bat :plexus-bootstrap:serverBootSmokeTest
```

Verifies rule approval and application on real 1.21.11 (`serverBootSmokeTest -PplexusMinecraft=1.21.11`). The client has not been run yet.

## Representative flows
`plexus:client_brand` (CLIENT) and `plexus:server_brand` (both) replace `"vanilla"` with `"plexus"`. The failure policy is DISABLE_RULE.

`plexus:client_title_mod_counts` (CLIENT, DISABLE_RULE) replaces the only `GuiGraphicsExtractor.text(Font, String, int, int, int)` call where the title screen's `TitleScreen.extractRenderState` draws the version text with `client.TitleScreenModCounts.onVersionText` in the game layer (`RedirectCall`, ADR-0013). The hook draws that line and draws the per-loader mod counts above it. MinecraftForge's patch lacks this call, so this rule is disabled on Forge, and `plexus:client_title_mod_counts_forge` in `plexus-bridge-forge` draws above the Forge branding instead.

## Differences from 26.1

**This is a supported version** (ADR-0033, promoted from experimental; ADR-0019 explains the remapped jar). Copied from `plexus-version-adapter-v26_1`. The rules whose targets were changed after locating them with `javap` in the remapped 1.21.11 jar are as follows:

- `plexus:use_entity`: `Player.interactOn(Entity, InteractionHand)`
- `plexus:client_hud`: `Gui.render`
- `plexus:client_creative_tabs_hovered`·`_drawn`: `CreativeModeInventoryScreen.render`·`renderBg`
- `plexus:client_title_mod_counts`: `GuiGraphics.drawString` in `TitleScreen.render`
- `plexus:client_world_painters`: `LevelRenderer.submitEntities` (`renderer.state.LevelRenderState`)
- `plexus:client_shader_uniforms`: `GlRenderPass.setPipeline` (`RenderPass` is an interface in 1.21.11)
- `plexus:client_fov`: `GameRenderer.getFov`

`BuiltInRulesTest` (game layer module) tests applying all rules to the remapped jar. `gameVersions()` contains only `1.21.11`. There are no hotfixes.

## Known limitations
The original client jar SHA-1 is `ba2df812…`. For client-only rules, only the transformation plan, preconditions, bytecode verification, and hook signatures have been verified without a window. Fabric mods are not yet blocked on 1.21.11 (intermediary names, ADR-0019).
