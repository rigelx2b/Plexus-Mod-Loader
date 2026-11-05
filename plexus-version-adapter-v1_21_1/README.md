[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-v1_21_1

## What this module does
Contains Minecraft 1.21.1-specific knowledge. It includes client/server entry points and built-in transformation rules (branding, title screen mod counts, registry, interaction, life cycle, event hooks, and the client input, HUD, rendering, networking and screen hooks).

## What it owns
`dev.plexusmodloader.game.version.v1_21_1.Minecraft1_21_1Adapter` and the `META-INF/services` registration.

## What it does not do
Does not support other versions. Does not place Minecraft names outside this module.

## Dependencies
`plexus-version-adapter-api`. Has no compile dependency on the Minecraft jar. Names are strings verified against the remapped Mojang 1.21.1 jar.

## Key entry points
`Minecraft1_21_1Adapter`

## Build and test
```powershell
.\gradlew.bat :plexus-version-adapter-v1_21_1:test
```

## Representative flows
`plexus:client_brand` (CLIENT) and `plexus:server_brand` (both) replace `"vanilla"` with `"plexus"`. The failure policy is DISABLE_RULE.

`plexus:client_title_mod_counts` (CLIENT, DISABLE_RULE) redirects the `GuiGraphics.drawString(Font, String, int, int, int)` call where `TitleScreen.render` draws the version text to `client.TitleScreenModCounts.onVersionText` in the game layer (`RedirectCall`, ADR-0013).

## Differences from 1.21.11 / 26.1

Minecraft 1.21.1 is a supported version since 2.0.0 (ADR-0035). It is obfuscated, so it runs on the game jar remapped to Mojang names (ADR-0019) and on Java 25.
Key target adjustments:
- Identifier changed from `net.minecraft.resources.Identifier` (26.x) to `net.minecraft.resources.ResourceLocation`.
- The network configuration phase exists (`ServerConfigurationPacketListenerImpl`, `CommonListenerCookie`, `ServerCommonPacketListenerImpl.handleCustomPayload(ServerboundCustomPayloadPacket)`), and `PlayerList.placeNewPlayer` takes `(Connection, ServerPlayer, CommonListenerCookie)V`, as in 1.21.11.
- `PlayerList.canPlayerLogin` takes `(SocketAddress, GameProfile)Component`.
- `PlayerList.respawn` takes `(ServerPlayer, boolean, Entity$RemovalReason)ServerPlayer`.
- `LivingEntity.hurt(DamageSource, float)Z` exists and `hurtServer` does not (1.21.11 has `hurtServer(ServerLevel, DamageSource, float)`).
- `Block.playerWillDestroy` returns `BlockState`.
- The creative screen uses `mouseClicked(double, double, int)`, `mouseReleased(double, double, int)`, `mouseDragged(double, double, int, double, double)` and `mouseScrolled(double, double, double, double)`.

These were checked with `javap` on the remapped 1.21.1 jars (2026-11-04).

## Client rules

All client rules are `CLIENT`-only with the failure policy ABORT_STARTUP (a missing hook target stops the launch instead of leaving a feature dead). Each was applied to the real remapped 1.21.1 classes by the game layer's `BuiltInRulesTest`. The 1.21.1 targets that differ from 1.21.11:

| Rule | 1.21.1 target |
| :--- | :--- |
| `plexus:client_key_input` | `KeyboardHandler.keyPress(JIIII)V` (window, key, scan code, action, modifiers) |
| `plexus:client_mouse_button` | private `MouseHandler.onPress(JIII)V` (window, button, action, modifiers) |
| `plexus:client_mouse_scroll` | private `MouseHandler.onScroll(JDD)V` |
| `plexus:client_hud` | `Gui.render(GuiGraphics, DeltaTracker)V`; F1 hides the HUD through `Options.hideGui` |
| `plexus:client_shader_uniforms` | head of `LevelRenderer.renderLevel(...)V` (there is no `GlRenderPass.setPipeline`) |
| `plexus:client_world_painters` | before the return of `LevelRenderer.renderLevel(...)V` (there is no `submitEntities`) |
| `plexus:client_fov` | `GameRenderer.getFov(Camera, float, boolean)D` (a `double`, not a `float`) |
| `plexus:client_particle_providers` | `ParticleEngine.registerProviders()V` (`ParticleResources` is 1.21.11) |
| `plexus:client_block_entity_renderers` | `BlockEntityRenderers.createEntityRenderers(...)Map` (renderers draw immediately, no render state) |
| `plexus:client_item_tooltip` | `ItemStack.getTooltipLines(Item$TooltipContext, Player, TooltipFlag)List`; it returns twice, the first for `HIDE_TOOLTIP` |
| `plexus:client_left_world` | `Minecraft.disconnect(Screen, Z)V` (1.21.11 has a third boolean) |
| `plexus:client_payloads`, `plexus:client_configuration_payloads` | `ClientPacketListener` and `ClientConfigurationPacketListenerImpl` `.handleCustomPayload(CustomPacketPayload)V` |
| `plexus:client_menu_screens` | `MenuScreens.create(MenuType, Minecraft, int, Component)V` |

## Limits

- There are no client-only features left unported: every client declaration and `ClientEvents` key has a rule. The differences that remain are in the game layer's `Limits` (shader dialect and location, post effect chain location, points as cubes, shader pipelines not rebuilt on a resource reload).
- None of the client rules was run in a game window; see the game layer's `Limits`.
