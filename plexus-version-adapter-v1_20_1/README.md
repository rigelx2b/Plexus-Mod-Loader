[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-v1_20_1

## What this module does
Contains Minecraft 1.20.1-specific knowledge. It includes client/server entry points and built-in transformation rules (branding, title screen mod counts, registry, interaction, life cycle, and event hooks).

## What it owns
`dev.plexusmodloader.game.version.v1_20_1.Minecraft1_20_1Adapter` and the `META-INF/services` registration.

## What it does not do
Does not support other versions. Does not place Minecraft names outside this module.

## Dependencies
`plexus-version-adapter-api`. Has no compile dependency on the Minecraft jar. Names are strings verified against the remapped Mojang 1.20.1 jar.

## Key entry points
`Minecraft1_20_1Adapter`

## Build and test
```powershell
.\gradlew.bat :plexus-version-adapter-v1_20_1:test
```

## Representative flows
`plexus:client_brand` (CLIENT) and `plexus:server_brand` (both) replace `"vanilla"` with `"plexus"`. The failure policy is DISABLE_RULE.

`plexus:client_title_mod_counts` (CLIENT, DISABLE_RULE) redirects the `GuiGraphics.drawString(Font, String, int, int, int)` call where `TitleScreen.render` draws the version text to `client.TitleScreenModCounts.onVersionText` in the game layer (`RedirectCall`, ADR-0013).

## Differences from 1.21.11 / 26.1

**This is a supported version** (ADR-0035; ADR-0019 explains the remapped jar). Minecraft 1.20.1 runs on Mojang-remapped jars and Java 25.
Key target adjustments:
- Identifier changed from `net.minecraft.resources.Identifier` (26.x) to `net.minecraft.resources.ResourceLocation`.
- Network configuration phase (`ServerConfigurationPacketListenerImpl`, `CommonListenerCookie`) does not exist in 1.20.1; payload handling targets `ServerboundCustomPayloadPacket`.
- `PlayerList.placeNewPlayer` takes `(Connection, ServerPlayer)V` without `CommonListenerCookie`.
- `PlayerList.canPlayerLogin` takes `(SocketAddress, GameProfile)Component`.
- `PlayerList.respawn` takes `(ServerPlayer, boolean)ServerPlayer` without `RemovalReason`.
- `LivingEntity.hurt` replaces `hurtServer` (which was introduced in 1.21).
- `Block.playerWillDestroy` returns `void`.
- Creative mode tabs use `double, double, int` mouse event handlers.

## Limits

- 1.20.1 has no configuration phase, so the rules of later versions for configuration payloads and tasks are replaced by login query rules (`plexus:login_exchange_hold`, `plexus:login_exchange_answer`, `plexus:client_login_query`).
