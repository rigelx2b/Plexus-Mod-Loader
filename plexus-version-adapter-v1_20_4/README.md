[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-v1_20_4

## What this module does
Contains Minecraft 1.20.4-specific knowledge. It includes client/server entry points and built-in transformation rules (branding, title screen mod counts, registry, interaction, life cycle, network payload, configuration phase and event hooks).

## What it owns
`dev.plexusmodloader.game.version.v1_20_4.Minecraft1_20_4Adapter` and the `META-INF/services` registration.

## What it does not do
Does not support other versions. Does not place Minecraft names outside this module.

## Dependencies
`plexus-version-adapter-api`. Has no compile dependency on the Minecraft jar. Names are strings verified against the remapped Mojang 1.20.4 jar.

## Key entry points
`Minecraft1_20_4Adapter`

## Build and test
```powershell
.\gradlew.bat :plexus-version-adapter-v1_20_4:test
.\gradlew.bat :plexus-game-v1_20_4:test "-PplexusMinecraft=1.20.4"   # BuiltInRulesTest applies every rule to the real 1.20.4 classes
```

## Representative flows
`plexus:client_brand` (CLIENT) and `plexus:server_brand` (both) replace `"vanilla"` with `"plexus"`. The failure policy is DISABLE_RULE.

`plexus:client_title_mod_counts` (CLIENT, DISABLE_RULE) redirects the `GuiGraphics.drawString(Font, String, int, int, int)` call where `TitleScreen.render` draws the version text to `client.TitleScreenModCounts.onVersionText` in the game layer (`RedirectCall`, ADR-0013).

`plexus:payload_read_serverbound` and `plexus:payload_read_clientbound` (both sides, ABORT_STARTUP) run `GameHooks.onReadPayload` at the head of the private static `readPayload(ResourceLocation, FriendlyByteBuf)` of the two custom payload packets; it returns the payload of a channel a mod declared (the game keeps only its own payloads and discards the rest), `null` for any other.

`plexus:configuration_tasks` and `plexus:configuration_tick` let mods' configuration exchanges run as configuration tasks: 1.20.4's `ConfigurationTask` has no tick of its own, so the listener's `tick` polls the waiting exchange.

## Differences from 1.21.11 / 26.1

Minecraft 1.20.4 runs on Mojang-remapped jars and Java 25 (ADR-0019, ADR-0035).
Key target adjustments:
- Identifier changed from `net.minecraft.resources.Identifier` (26.x) to `net.minecraft.resources.ResourceLocation`.
- The network configuration phase exists (`ServerConfigurationPacketListenerImpl`, `CommonListenerCookie`, `ServerCommonPacketListenerImpl`), and `PlayerList.placeNewPlayer` takes `(Connection, ServerPlayer, CommonListenerCookie)V`.
- Custom payloads are `CustomPacketPayload` records of an ID with `write(FriendlyByteBuf)`; there is no `StreamCodec` and no `CustomPacketPayload.codec`.
- `PlayerList.canPlayerLogin` takes `(SocketAddress, GameProfile)Component`.
- `PlayerList.respawn` takes `(ServerPlayer, boolean)ServerPlayer` without `Entity$RemovalReason` (1.21.1 has it).
- `LivingEntity.hurt(DamageSource, float)Z` exists and `hurtServer` does not.
- `Block.playerWillDestroy` returns `BlockState` (1.20.1 returns `void`).
- The creative screen uses `mouseClicked(double, double, int)`, `mouseReleased(double, double, int)`, `mouseDragged(double, double, int, double, double)` and `mouseScrolled(double, double, double, double)`.

These were checked with `javap` on the remapped 1.20.4 jars (2026-11-04).

## Limits

- The client rules (`plexus:client_*`: key bindings, key, mouse button and scroll input, tick, FOV, join and leave, HUD, client and configuration payloads, menu screens, particles, block entity and entity renderers, asset reload, tooltips, world painters, creative tab pages) are checked against the real 1.20.4 classes by `BuiltInRulesTest` (they plan, apply and find their hooks) and listed in `Minecraft1_20_4AdapterTest`, but no window has opened: their behavior in a running client is not yet verified.
- The 26.x rules for the early window title and loading overlay, and for render pipelines (`plexus:client_shader_uniforms`), do not exist on 1.20.4. Mod shader pipelines are built in the game layer from a program file it writes, in the GLSL 1.50 core dialect (see `plexus-game-v1_20_4`, README).
- `plexus:client_world_painters` runs at the end of `LevelRenderer.renderLevel` (every return), and `plexus:client_hud` at the end of `Gui.render(GuiGraphics, float)`.
