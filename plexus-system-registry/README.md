[English](README.md) | [한국어](README.ko.md)

# plexus-system-registry

## What This Module Does
Implements built-in system definitions, replacement conflict resolution, provider creation and validation, and active system lookups.

## What It Owns
`dev.plexusmodloader.runtime.system`(`BuiltInSystems`, `SystemDefinition`, `SystemReplacementResolver`, `SystemRegistry`, `PlexusGameBrandProvider`, `SystemProblemCode`)

## What It Does Not Do
Does not connect directly to the game (that is a version adapter hook). Does not discover mods.

## Dependencies
`plexus-system-api`(api), `plexus-mod-metadata`

## Key Entry Points
`new SystemReplacementResolver().resolve(mods, definitions)` → `SystemRegistry.create(definitions, result, side, modClassLoader)`

## Build and Test
```powershell
.\gradlew.bat :plexus-system-registry:check
```

6 tests in `SystemReplacementResolverTest`, 7 tests in `SystemRegistryTest` (including 5 types of invalid providers)

## Typical Flow
If there are 0 replacements, the default implementation is used; if there is 1, that implementation is used. If there are 2 or more, only an `override` with a dependency relationship is accepted, and anything else results in `PLEXUS-SYSTEM-001`. After creation, it verifies contract interface implementation and checks system-specific validators.

## Known Limitations
There are currently no dependencies or initialization ordering between systems.
