[English](README.md) | [한국어](README.ko.md)

# plexus-system-api

## What this module does
Defines the public contracts for replaceable Plexus Mod Loader systems. Includes system IDs, providers, lookups, and the contract for the first system, `plexus:game_brand`.

## What it owns
`dev.plexusmodloader.api.system`(`SystemId`, `PlexusSystem`, `SystemProvider`, `SystemContext`, `SystemLookup`), `...system.brand.GameBrandSystem`

## What it does not do
Does not decide replacements or create implementations (that is `plexus-system-registry`). Does not expose Minecraft types.

## Dependencies
`plexus-runtime-api`(api)

## Key entry points
Mods: Implement `SystemProvider<T>` and declare it in `replacesSystems` in `plexus.mod.json`. Lookup is done via `ModContext.systems().get(Contract.class)`.

## Build and test
```powershell
.\gradlew.bat :plexus-system-api:check
```

## Typical flow
Mod declaration → Conflict resolution → Provider creation and validation → Version adapter hook uses the active implementation via `SystemLookup`.

## Known limitations
There is currently only one system: `plexus:game_brand`. There is no extension (`EXTEND`); only full replacement is supported.
