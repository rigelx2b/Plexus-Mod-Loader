[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-v26_2

## What This Module Does
Contains Minecraft 26.2-specific knowledge. It includes client/server entry points and dozens of built-in transformation rules (branding, title screen mod counts, registry and payload handling, Native API event hooks, etc.).

## What It Owns
`dev.plexusmodloader.game.version.v26_2.Minecraft26_2Adapter` and `META-INF/services` registration

## What It Does Not Do
Does not support other versions. Does not place Minecraft names outside this module.

## Dependencies
`plexus-version-adapter-api`. Has no compile dependency on the Minecraft jar. Names are strings verified with `javap`.

## Key Entry Point
`Minecraft26_2Adapter`

## Build and Test
```powershell
.\gradlew.bat :plexus-bootstrap:serverBootSmokeTest
```

Verifies rule approval and application on real 26.2. The client is verified with `:plexus-bootstrap:runClient`.

## Representative Flows
`plexus:client_brand` (CLIENT) and `plexus:server_brand` (both sides) replace `"vanilla"` with `"plexus"`. The failure policy is DISABLE_RULE.

`plexus:client_title_mod_counts` (CLIENT, DISABLE_RULE) replaces the sole `GuiGraphicsExtractor.text(Font, String, int, int, int)` call where `TitleScreen.extractRenderState` on the title screen draws the version text with `client.TitleScreenModCounts.onVersionText` in the game layer (`RedirectCall`, ADR-0013). The hook draws that line and draws the per-loader mod counts above it. MinecraftForge's patch lacks this call, so this rule is disabled on Forge, and `plexus:client_title_mod_counts_forge` from `plexus-bridge-forge` draws on top of Forge branding instead.

`plexus:builtin_registries_refreeze` (all sides, DISABLE_RULE, after `plexus:content_registration`) replaces the sole `bindBootstrappedTagsToEmpty(Registry)` call in `BuiltInRegistries.freeze()` with `BuiltInRegistryFreeze.bindBootstrappedTagsToEmpty` in the game layer (`RedirectCall`, ADR-0026 38). The hook binds tags like vanilla on unfrozen registries, and skips binding on already-frozen registries. Fabric API refreezes built-in registries after mod initialization, whereas under NeoForge, NeoForge's `GameData.freezeData` has already bound and frozen them. Behavior is identical in executions that freeze registries only once (vanilla, Fabric only, NeoForge only). Verification: 26.2 client and server jars and the client jar patched by NeoForge 26.2.0.93 (`javap`).

`plexus:custom_payload_encoding` (all sides, DISABLE_RULE) replaces the sole `StreamCodec.encode` call in `CustomPacketPayload$1.writeCap` with `HostPayloadHooks.encode` in the game layer (`RedirectCall`, after Mixin phase, ADR-0026 40). When there is no host loader, it behaves identically to the original call, and when NeoForge is the host, it writes NeoForge's own payloads using NeoForge codecs.

## Known Limitations
Target jar SHA-1 hashes are client `2dc72797…` and server bundler `823e2250…`. Version 26.3 and above requires a new adapter module.
