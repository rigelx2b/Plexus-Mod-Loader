[English](README.md) | [한국어](README.ko.md)

# plexus-event-api

## What This Module Does
Defines the public API for mods to receive game events. Consists of typed event keys, listener registration, and server lifecycle events.

## What It Owns
`dev.plexusmodloader.api.event` (`EventKey`, `EventRegistry`, `EventPriority`, `EventResult`), `...event.server` (`ServerEvents`, `ServerView`, 5 listener types)

## What It Does Not Do
Does not fire events directly. Firing is handled by version adapter hooks and the `EventBus` of `plexus-runtime-impl`. Does not expose Minecraft types.

## Dependencies
`plexus-runtime-api` (api)

## Key Entry Points
`ModContext.events().register(ServerEvents.STARTED, server -> ...)`

Priority: `ModContext.events().register(BlockEvents.BREAK, EventPriority.HIGH, (world, pos, block, player) -> EventResult.DENY)`

## Build and Test
```powershell
.\gradlew.bat :plexus-runtime-impl:test --tests *EventBusTest
.\gradlew.bat :plexus-bootstrap:worldCreateSaveTest
```

## Typical Flow
The `MinecraftServer.tickServer` entry hook fires `STARTED` on each server's first tick, followed by `TICK` on every tick thereafter. The `stopServer` entry hook fires `STOPPING`. `STARTING` (before world creation) and `STOPPED` (after world shutdown) are fired by `LifecycleHooks` in the game layer. If a listener throws an exception, only that listener is disabled and reported as `PLEXUS-EVENT-001`.

Listeners execute in `EventPriority` order (`HIGHEST`→`LOWEST`), and in registration order within the same priority. Decision events (events where listeners return `EventResult` or `InteractionResult`) terminate at the first listener that returns a value other than `PASS`, and subsequent listeners are not invoked. If a decision listener returns `null`, it is reported as `PLEXUS-EVENT-003` and disabled.

## Known Limitations
Events in this module are limited to the server lifecycle. Game events (block, entity, player, world) are in `plexus-game-api`. NeoForge's `receiveCanceled` (a listener that receives an already canceled event to revert the cancellation) cannot be represented. Because `ServerView.dedicated()` is a value reported by Minecraft, game test servers report `false`.
