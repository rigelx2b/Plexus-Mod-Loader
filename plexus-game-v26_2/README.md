[English](README.md) | [한국어](README.ko.md)

# plexus-game-v26_2 — Minecraft 26.2 Game Implementation Layer

`plexus-game-v26_2` is a game layer module directly bound to Minecraft 26.2 that instantiates Native API declarations from `plexus-game-api` into actual Minecraft game engine objects, and executes bytecode hooks and custom rendering UI.

---

## 1. Key Responsibilities and Components

### 1) Native API Object Instantiation
- `ContentInstaller`: Directly registers `PlexusBlock` and `PlexusItem` declared by mods into the original Minecraft registries (`BuiltInRegistries`).
- `Views`: Wraps vanilla Minecraft `ServerLevel`, `Entity`, and `ServerPlayer` objects into safe, immutable `WorldView` and `PlayerView` interfaces.
- `commands.CommandInstaller`: Registers platform-neutral command trees into the vanilla Brigadier command dispatcher.

### 2) In-Game Hooks (Runtime Hooks)
- `LifecycleHooks`: Intercepts server tick, level load/unload, entity spawn, and player respawn events.
- `InteractionHooks`: Handles block breaking, item usage, and entity right-click interaction callbacks.
- `HostPayloadHooks`: Coordinates decoding and routing so that custom network payloads from Forge/NeoForge and Fabric do not collide on a single network channel.

### 3) Loading Screen and Window Title (`PlexusLoadingOverlay`, `PlexusWindowTitle`)
- The game's loading screen (`LoadingOverlay`) is left intact (Mojang red background or black background under monochrome logo settings, Mojang Studios logo, progress bar, fade). `plexus:client_loading_overlay` overlays the Plexus indicators with the same fade at the end of `extractRenderState`: the Plexus logo in the lower right with chevrons orbiting the core (`plexus_cube.png`, 120 frames of 32×32, 7.2 seconds per rotation) and `PLEXUS MOD LOADER`, along with per-loader mod counts below the progress bar (`MODS: FABRIC 34 · NEOFORGE 53`).
- Text is drawn using 5×7 pixel characters instead of the game font. MinecraftForge's initial loading screen occurs before the game loads fonts.
- MinecraftForge's `ForgeLoadingOverlay` draws the same screen, so it receives the same indicators. The cube and title for the Forge and NeoForge early window are handled by `EarlyScreenArt` and `EarlyWindowTitle` in `plexus-bridge-forge`.
- `plexus:client_window_title`: Sets the window title to `Minecraft* Plexus 26.2` regardless of the loader (subsequent suffixes such as ` - Singleplayer` remain as in the vanilla game).

---

## 2. ClassLoader Separation Principles

- While classes in this module depend on Minecraft 26.2 vanilla code at compile time, they are loaded exclusively by the **game classloader (`GameClassLoader`)**.
- The bootstrap classloader and runtime core modules cannot directly statically reference classes in this module; they are invoked solely through version adapter contracts.
- Client subpackages (`client.*`) are isolated to prevent them from being referenced in server environments.

---

## 3. Verification and Testing (Gradle Tasks)

```powershell
# Transformation rule integrity verification (BuiltInRulesTest)
.\gradlew.bat :plexus-game-v26_2:check

# 26.2 game runtime Native API integration test
.\gradlew.bat :plexus-bootstrap:nativeApiGameTests
```
