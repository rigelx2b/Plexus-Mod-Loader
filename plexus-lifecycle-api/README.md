[English](README.md) | [한국어](README.ko.md)

# plexus-lifecycle-api

## What This Module Does
Defines entrypoint interfaces implemented by mods and the `ModContext` passed to entrypoints by Plexus Mod Loader.

## What It Owns
`dev.plexusmodloader.api.lifecycle` package

## What It Does Not Do
Does not handle entrypoint invocation order or error handling. That is handled by `EntrypointInvoker` in `plexus-runtime-impl`.

## Dependencies
`plexus-runtime-api` (api)

## Key Entry Points
`CommonModInitializer`, `ClientModInitializer`, `DedicatedServerModInitializer`, `ModContext`

## Build and Test
```powershell
.\gradlew.bat :plexus-lifecycle-api:check
```

## Typical Flow
Reads `entrypoints.common/client/server` from `plexus.mod.json`. Plexus Mod Loader invokes all common entrypoints in dependency order, then invokes side-specific entrypoints.

## Known Limitations
Entrypoints are only invoked before the game `main`. Subsequent points in time (server start/tick/stop, world load/save/unload, etc.) are handled via events rather than entrypoints: register `ServerEvents` from `plexus-event-api` or events from `plexus-game-api` with `ModContext.events()`. Shutdown logic is registered via `ModContext.onShutdown` and executed when the JVM terminates. Actions to perform when a world closes are handled via events such as `ServerEvents.STOPPED`.
