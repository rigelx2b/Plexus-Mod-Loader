[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-v26_3

## What this module does
Contains Minecraft 26.3-specific knowledge. Houses client/server entrypoints and dozens of built-in transformation rules (branding, title screen mod counts, registry and payload handling, Native API event hooks, etc.).

## What it owns
`dev.plexusmodloader.game.version.v26_3.Minecraft26_3Adapter` and the `META-INF/services` registration

## What it does not do
Does not support other versions. Does not expose Minecraft names outside this module.

## Dependencies
`plexus-version-adapter-api`. Has no compile-time dependency on the Minecraft jar. Names are strings verified via `javap`.

## Primary entrypoint
`Minecraft26_3Adapter`

## Build and test
```powershell
.\gradlew.bat :plexus-bootstrap:serverBootSmokeTest
```

Verifies rule approval and application on actual 26.3. The client is verified with `:plexus-bootstrap:runClient`.

## Representative flows
`plexus:client_brand` (CLIENT) and `plexus:server_brand` (both sides) replace `"vanilla"` with `"plexus"`. The failure policy is DISABLE_RULE.

`plexus:client_title_mod_counts` (CLIENT, DISABLE_RULE) replaces the sole `GuiGraphicsExtractor.text(Font, String, int, int, int)` call where the title screen's `TitleScreen.extractRenderState` draws the version text with `client.TitleScreenModCounts.onVersionText` in the game layer (`RedirectCall`, ADR-0013). The hook renders that line and draws the per-loader mod counts above it. Because MinecraftForge's patch omits this call, this rule is disabled on Forge, and `plexus-bridge-forge`'s `plexus:client_title_mod_counts_forge` draws above Forge branding instead.

`plexus:builtin_registries_refreeze` (all sides, DISABLE_RULE, after `plexus:content_registration`) replaces the sole `bindBootstrappedTagsToEmpty(Registry)` call in `BuiltInRegistries.freeze()` with `BuiltInRegistryFreeze.bindBootstrappedTagsToEmpty` in the game layer (`RedirectCall`, ADR-0026 38). The hook binds tags like vanilla on registries that are not frozen, and skips binding on registries that are already frozen. Fabric API refreezes built-in registries once more after mod initialization because under NeoForge, NeoForge's `GameData.freezeData` has already bound and frozen them. In runs that freeze registries only once (vanilla, Fabric only, NeoForge only), the behavior is identical. Verification: 26.3 client and server jars and the client jar patched by NeoForge 26.2.0.93 (`javap`).

`plexus:custom_payload_encoding` (all sides, DISABLE_RULE) replaces the sole `StreamCodec.encode` call in `CustomPacketPayload$1.writeCap` with `HostPayloadHooks.encode` in the game layer (`RedirectCall`, after the Mixin phase, ADR-0026 40). When there is no host loader, it behaves the same as the original call; when NeoForge is the host, it writes NeoForge's own payloads using NeoForge's codec.

## Differences from 26.2

Supported version starting from 1.2.0 (`supported` in `gradle/minecraft-versions.properties`). Current status is in `docs/plans/MINECRAFT_26_3.md` (`docs/` is not in this public repository). Copied from `plexus-version-adapter-v26_2` using `newMinecraftVersion -Pexperimental=true`. The single rule retargeted after locating it with `javap` in the 26.3 jar is:

- `plexus:client_shader_uniforms`: `com.mojang.renderpearl.frontend.FrontendRenderPass.setPipeline(CompiledRenderPipeline)` (`RenderPass` in 26.3 is an interface, and this class is its sole implementation)

`BuiltInRulesTest` (game layer module) applies all rules against the 26.3 jar. `gameVersions()` contains only `26.3`. There are no hotfixes.

## Known limitations
Target jar SHA-1 hashes are client `2dc72797…` and server bundler `823e2250…`. Versions newer than 26.3 require a new adapter module.
