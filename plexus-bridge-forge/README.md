[English](README.md) | [한국어](README.ko.md)

# plexus-bridge-forge — MinecraftForge & NeoForge Compatibility Bridge

`plexus-bridge-forge` is the core compatibility bridge module that virtualizes **MinecraftForge** and **NeoForge** mods on top of Plexus Mod Loader's single JVM runtime, orchestrating bytecode and the FML service layer so they can coexist with Fabric and Quilt mods.

---

## 1. Key Roles and Design Architecture

### 1) FML (Forge Mod Loader) Runtime Virtualization
Forge and NeoForge were designed assuming their own classloader structures (ModLauncher and FML module layers). Plexus Mod Loader virtualizes them in the following ways:
- **`cpw.mods.modlauncher` Contract Implementation:**
  - Without duplicating ModLauncher (LGPL) code, provides a lightweight virtualization service (`src/forgeHost`, `forge-host.jar`) that independently implements its public interface contract.
  - Plexus Mod Loader's single game classloader (`GameClassLoader`) defines FML's BOOT, SERVICE, PLUGIN, and GAME layers as modules.
- **NeoForge FML Hosting (FML 11 for 26.1 and 26.2, FML 12 for 26.3):**
  - Directly integrates `FMLLoader`'s game layer creation and mod discovery into the in-game bytecode transformation chain via `PlexusFml` in `neoforge-host.jar` (`src/neoForgeHost`).

### 2) Single-Game Coexistence with Fabric and Quilt Mods
- **Unified Mixin Engine (`FmlSharedMixin`):**
  - Intercepts Forge/NeoForge from starting a separate Mixin instance, allowing a single FabricMC SpongePowered Mixin 0.8.7 engine to unify and process the Mixin configurations of all loaders.
- **Dynamic Registry Control (`NeoForgeRegistryOpening`):**
  - NeoForge freezes registries early during boot. Plexus Mod Loader temporarily and safely opens the root registries while Fabric mod initialization entrypoints are executing, preventing `Registry is already frozen` exceptions.
- **Dynamic Host Patch Adaptation (`HostPatchAdjustments` & `NeoForgeInjectionPoints`):**
  - When binary patches by Forge or NeoForge shift existing Mixin injection points (method calls, field accesses, etc.) in vanilla classes, retargets the bytecode injection targets to the correct replacement methods at runtime.
  - Injection points for some Fabric API modules such as `fabric-content-registries-v0` are shifted this way and function as-is. Modules containing unshiftable mixins are excluded via `PLEXUS-COMPAT-224` (last measured on 26.2: 10 alongside Forge, 16 alongside NeoForge).
  - In addition to rules that shift calls, `ForgeInjectionPoints` includes the `HOST_COVERS` rule (drops only that handler if the host's own code already does the work of a single handler) and the `HOST_NEUTRAL_ARGUMENT` rule (executes a `@Redirect` for a call where the host added an argument only when it is a neutral value) (ADR-0032). `HOST_COVERS` is used only for hand-written rules inspected per handler; alongside Forge, Fabric renderer models that select per-position particles or in-wall sprites display default sprites, and Fabric data generation does not automatically create block item models.

### 3) Cross-Loader Mod Visibility
- **`ForeignModList` / `ForeignModIndex`:**
  - Allows Fabric mods to query Forge mods via `FabricLoader.getInstance().isModLoaded(...)`, and
  - Conversely, responds with the correct `IModInfo` metadata when Forge/NeoForge mods query Fabric/Quilt mods via `ModList.get().isLoaded(...)`.

---

## 2. Key Components

| Package / Class | Role |
| :--- | :--- |
| `dev.plexusmodloader.compat.forge.NeoForgeBridge` | Detects the NeoForge environment, downloads installation files, and manages lifecycle |
| `dev.plexusmodloader.compat.forge.NeoForgeGameHost` | Defines the NeoForge FML module layer and manages transformation targets |
| `dev.plexusmodloader.compat.forge.NeoForgeInjectionPoints` | Collection of injection point replacement rules for vanilla methods modified by NeoForge patches |
| `dev.plexusmodloader.compat.forge.MinecraftForgeBridge` | Applies the MinecraftForge binary patch set and sets up the host |
| `dev.plexusmodloader.compat.forge.ForgeInjectionPoints` | Collection of injection point replacement rules addressing MinecraftForge patches |
| `dev.plexusmodloader.compat.forge.ForgeForeignPayloads` | The MinecraftForge counterpart of `ForeignPayloads` (`ADR-0026`). Installed by `ForgeGameHost` when Fabric or Quilt mods share the game. Because Fabric API networking codecs take precedence on plugin channel IDs `minecraft:register` and `minecraft:unregister`, prevents `ClassCastException` caused by Forge's channel list (`ForgePayload`) being written with that codec. For IDs with Forge channels (`NetworkRegistry.findTarget`), writes `ForgePayload` using Forge's own codec (`ForgeHooks.getCustomPayloadCodec`) (the wire format for plugin channels is identical). Forge lacks per-connection channel validation, so no translation rules are required. Invoked by `HostPayloadHooks.encode` in the game layer (rule `plexus:custom_payload_encoding`). |
| `dev.plexusmodloader.compat.forge.ForeignModIndex` | Converts mod metadata from other loaders into FML SPI format |
| `dev.plexusmodloader.compat.forge.EarlyScreenArt` | Replaces the animation (anvil/fox) and logos/icons on the Forge and NeoForge early loading window with the Plexus cube (`earlyscreen/*.png`) on the client. Only when original image dimensions match verified values do module reader `find` and `open` return a copy from `<game folder>/.plexus/early-screen`, applied only when Plexus Mod Loader's own early window is not shown (`-Dplexus.earlyWindow=off` or the window could not open). |
| `dev.plexusmodloader.compat.forge.ForgeEarlyWindowOff` | When Plexus Mod Loader's own early window is displayed, removes the `ImmediateWindowProvider` service from MinecraftForge's `fmlearlydisplay` module so that FML does not spawn its own early window and takes the disabled path (identical to `DummyProvider` and `earlyWindowControl = false` in `fml.toml`). |
| `dev.plexusmodloader.compat.forge.ForgeConfigsBeforeFirstFrame` | Intercepts the `ImmediateWindowHandler.loadingOverlay` call in the `Minecraft` constructor on MinecraftForge clients via rule `plexus:forge_configs_before_first_frame`, delaying the first frame until Forge completes `CONFIG_LOAD` (reading client and common mod configs), up to 20 seconds or upon load failure. Prevents hangs on fresh installations caused by mods that read configs every frame (Shoulder Surfing Reloaded). |
| `dev.plexusmodloader.compat.forge.EarlyWindowTitle` | Changes the early loading window title to `Minecraft* Plexus <version>` (only when the title constant in `DisplayWindow` occurs exactly once, modifying only the constant pool string while preserving code and stack maps). |

---

## 3. Verification and Testing (Gradle Tasks)

```powershell
# Unit tests (including `ForgeForeignPayloadsTest`) and injection point replacement rule verification
.\gradlew.bat :plexus-bridge-forge:check

# Forge 26.2 + Plexus Mods integrated game test
.\gradlew.bat :plexus-bootstrap:forgeGameTest

# NeoForge 26.2 + Plexus Mods headless game test
.\gradlew.bat :plexus-bootstrap:neoForgeGameTest

# Headless verification of combined popular Forge + Fabric mod corpus (Corpus)
.\gradlew.bat :plexus-bootstrap:corpusFabricForge
```

The tasks in `plexus-bootstrap` require `test-mods/` (and the mod corpus list `plexus-bootstrap/compat/`), so they do not run in this public repository.

---

## 4. Constraints and Caveats

- **Cannot Run Forge and NeoForge Simultaneously (`PLEXUS-COMPAT-221`):**
  Because MinecraftForge and NeoForge have conflicting vanilla bytecode patches and core class inheritance structures, when mods from both loaders are provided simultaneously, Forge is launched first and NeoForge mods are safely excluded with an explicit reason.
- **Remote Download Integrity:**
  Original Forge and NeoForge JARs and FML dependency libraries are not included in the repository for copyright protection; on the first run in a user environment, they are downloaded from official Maven repositories and their SHA-1 checksums are strictly verified.
