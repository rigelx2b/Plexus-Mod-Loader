[English](README.md) | [한국어](README.ko.md)

# plexus-game-api — Clean Native Game Abstraction Layer

`plexus-game-api` is an interface module providing the dedicated **Plexus Native API** to handle blocks, items, entities, commands, networking, and client rendering features without directly exposing original Minecraft classes.

---

## 1. Design Principles: Zero Vanilla Coupling (Zero Coupling)

- **Version Independence:**
  Does not import any `net.minecraft.*` vanilla classes at all.
  Plexus Mods developed solely with the Native API can run through compatibility layers (`plexus-game-v26_*`) without recompilation even when Minecraft versions are upgraded.
- **Immutable View Pattern:**
  Ensures safe state access through safe wrapper view interfaces such as `WorldView`, `EntityView`, and `PlayerView`, instead of directly manipulating original Minecraft objects.

---

## 2. Provided Packages and Key APIs

| Package | Description |
| :--- | :--- |
| `dev.plexusmodloader.api.resource` | Namespaced resource identifiers (`ResourceId.of("mymod", "ruby")`) |
| `dev.plexusmodloader.api.content.block` | Block definitions, materials, hardness, drop tables, and event callbacks |
| `dev.plexusmodloader.api.content.item` | Item definitions, durability, and Creative Tab bindings |
| `dev.plexusmodloader.api.world` | `WorldView`, `BlockPos`, dimension identifiers, and block state queries |
| `dev.plexusmodloader.api.entity` | `EntityView`, `PlayerView`, inventory, and position views |
| `dev.plexusmodloader.api.event` | Core lifecycle events such as player join/leave, block breaking, and server ticks |
| `dev.plexusmodloader.api.command` | Platform-agnostic command tree builder (`CommandNode`) and permission controls |
| `dev.plexusmodloader.api.network` | Custom payload channel registration, binary codec encoding/decoding |
| `dev.plexusmodloader.api.client` | Key binding registration, custom HUD overlay rendering |
| `dev.plexusmodloader.api.devtools.gametest` | In-game automated test harness (`GameTest`) registration |

---

## 3. Verification and Testing (Gradle Tasks)

```powershell
# Run unit tests
.\gradlew.bat :plexus-game-api:check

# Integration tests for Plexus Mods in the actual game runtime
.\gradlew.bat :plexus-bootstrap:nativeApiGameTests
```
