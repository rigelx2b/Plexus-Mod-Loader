[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-api

## What this module does
Defines adapter contracts that contain knowledge specific to each Minecraft version (game entrypoints, built-in transformation rules).

## What it owns
`dev.plexusmodloader.game.version.GameVersionAdapter`, game layer contracts in `dev.plexusmodloader.game.version.layer` (`GameLayer`, `GameLayerContext`, `LoadedModCounts`, `ModCountLines`, `LoadingScreenModLines`, `NotLoadedModsNotice`, etc.)

`ModCountLines` determines the per-loader mod count lines displayed on the title screen (in the order Plexus, Fabric, Quilt, Forge, NeoForge; loaders with zero mods have no line) and their translation keys (`plexus.title.mod_count.*`). Translations are located in `en_us.json` and `ko_kr.json` under `src/main/resources/assets/plexus/lang/`; if a language file is missing or text formatting fails, it falls back to the English lines. For each loader line, the bundled mod count is appended after the directly installed mod count via `plexus.title.mod_count.bundled` (`Fabric mods: 2 (+13 included)`), and if there are mods in the mods folder that were not loaded, a `plexus.title.mod_count.left_out` line is added.

`LoadingScreenModLines` determines the lines rendered below the progress bar by the early window and the game layer's loading screen (`PlexusLoadingOverlay`). The first line is `Mods: Fabric 2 (+13 included)  ·  NeoForge 2` below the bar; if there are mods in the mods folder that were not loaded, those lines appear in amber directly above the bar (between the logo and the bar) such as `1 not loaded: sophisticatedbackpacks - see logs/latest.log` (`INTERNAL`, English only: both screens render using Plexus pixel glyphs before the game's fonts and languages are loaded). `LoadedModCounts` holds the directly installed mod counts (`installed`), not loaded mods (`leftOutMods`), and the IDs of mods that Plexus Mod Loader cannot yet run (`PLEXUS-COMPAT-224`) (`hostIncompatibleModIds`).

`NotLoadedModsNotice` creates the title and paragraphs for the notice dialog displayed over the title screen when it first appears after loading if any mods were not loaded (`INTERNAL`). These include a paragraph listing `224` mods by ID, a paragraph listing mods excluded from the mods folder for other reasons, and a paragraph stating that the reasons can be found in `logs/latest.log`. Texts are retrieved using translation keys `plexus.notice.not_loaded.title`, `.host_incompatible`, `.left_out`, and `.details` (`en_us`, `ko_kr`), falling back to English if keys are missing. The notice dialog remains until the player presses OK (or Esc) and appears once per run.

## What it does not do
Does not contain version-specific class or method names.

## Dependencies
`plexus-runtime-api`, `plexus-transform-api` (api)

## Key entrypoints
`GameVersionAdapter` (discovered via `ServiceLoader`)

## Build and test
```powershell
.\gradlew.bat :plexus-version-adapter-api:check
```

Contract usage is verified with `TestGameVersionAdapter` in `PlexusRuntimeTest`. `ModCountLinesTest` verifies lines in both languages and the English fallback.

## Typical flow
The runtime selects the adapter whose `gameVersion()` exactly matches the `version.json` id of the game jar. If none matches, `PLEXUS-BOOTSTRAP-002`.

## Known limitations
There is no version range matching; it matches exact versions only.
