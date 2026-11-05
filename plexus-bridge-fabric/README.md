[English](README.md) | [한국어](README.ko.md)

# plexus-bridge-fabric — Fabric & Quilt Compatibility Bridge

`plexus-bridge-fabric` is a bridge module that enables **Fabric** mods and **Quilt** mods to run on Plexus Mod Loader with 100% identical semantics and runtime reliability to the actual Fabric Loader / Quilt Loader.

---

## 1. Key Roles and Design Architecture

### 1) Complete Metadata Analysis and Version Resolution
- **Fabric mods (`fabric.mod.json`):**
  - Embeds the official Fabric Loader version specification (`VersionPredicate`) engine to strictly resolve `depends`, `recommends`, `breaks`, `conflicts`, and `provides` relationships.
  - Precisely reports malformed metadata as `PLEXUS-COMPAT-100` instead of throwing unhandled exceptions.
- **Quilt mods (`quilt.mod.json`):**
  - Conforms to the Quilt Loader 0.31.0 specification to parse multi-level dependencies (`optional`, `unless`, `load_type`, etc.) (`PLEXUS-COMPAT-300..310`).
  - Mods containing both `fabric.mod.json` and `quilt.mod.json` are automatically determined as Fabric mods and loaded stably.

### 2) Public Loader API Emulation
- Provides built-in public APIs for `FabricLoader.getInstance()` as well as Quilt's `QuiltLoader` and `MinecraftQuiltLoader`.
- Maps game object lookups, the shared object storage (`ObjectShare`), the configuration directory (`config/`), and entrypoint containers to states fully compatible with the real loaders.

### 3) Bytecode Transformation Pipeline (`FabricClassTransformer`)
When mod classes are loaded, transformations are applied in the exact same order as Fabric Loader:
1. **Environment Stripping (`EnvironmentStripper`):**
   - Inspects `@Environment(EnvType.CLIENT/SERVER)` annotations to safely strip methods/fields on the opposite side.
   - For Quilt mods, comprehensively handles `@ClientOnly`, `@DedicatedServerOnly`, and `@Requires` annotations as well.
2. **Interface Injection (`InterfaceInjector`):**
   - Parses `inject-interface` syntax defined in `accessWidener` to inject interface implementations into target Minecraft classes.
3. **Unified Mixin Engine (`PlexusMixinService`):**
   - Runs SpongePowered Mixin 0.8.7 (FabricMC fork) and MixinExtras 0.5.5 as a single service.
   - Binds Quilt mod mixin configurations as well to transform classes in a single pass.

### 4) Entrypoint Lifecycle Management & Language Adapters
- **Language Adapters:** Supports custom language adapters declared in Fabric's `languageAdapters` and Quilt's `quilt_loader.language_adapters` (implementing `org.quiltmc.loader.api.LanguageAdapter` or `net.fabricmc.loader.api.LanguageAdapter`). Adapters from both loaders are unified in a single runtime registry, allowing cross-loader entrypoint instantiation. Unresolved adapters block the mod with `PLEXUS-COMPAT-312` (Quilt) or `PLEXUS-COMPAT-114` (Fabric).
- **`pre_launch` / `preLaunch`:** Called immediately after Mixin preparation and right before game initialization.
- **`main` / `client` / `server`:** Called at Minecraft internal initialization points according to each loader's interface contract.

---

## 2. Key Packages and Classes

| Package | Key Classes | Role |
| :--- | :--- | :--- |
| `dev.plexusmodloader.compat.fabric` | `FabricBridge`, `ClassTweakerFile`, `FabricClassTransformer` | Fabric mod discovery and transformation pipeline management |
| `dev.plexusmodloader.compat.fabric.mixin` | `FabricMixinBootstrap`, `PlexusMixinService`, `HostPatchAdjustments`, `HostStaticCalls`, `HostTargetCalls`, `ReplacedReceiver`, `HostNeutralArguments` | Unified Mixin execution and automatic host patch adaptation. Bridges connecting `@WrapOperation`/`@Redirect` to calls modified by the host (static helpers, target class self-method invocations, neutral values for overloads with added arguments) |
| `dev.plexusmodloader.compat.fabric.loader` | `FabricLoaderRuntime`, `OfficialMappingResolver` | Fabric Loader public API runtime implementation |
| `dev.plexusmodloader.compat.quilt` | `QuiltBridge`, `QuiltModJsonReader`, `QuiltLoaderRuntime` | Quilt metadata parser and Quilt Loader API implementation |

---

## 3. Verification and Testing (Gradle Tasks)

```powershell
# Unit tests and Quilt API link verification
.\gradlew.bat :plexus-bridge-fabric:check

# Mixin, MixinExtras, and interface injection verification in a real 26.2 server environment
.\gradlew.bat :plexus-bootstrap:fabricMixin

# Headless verification of a large-scale real-world popular Fabric mod corpus
.\gradlew.bat :plexus-bootstrap:corpusFabric
```

The tasks in `plexus-bootstrap` require `test-mods/` (and the mod corpus list `plexus-bootstrap/compat/`), so they cannot be run in this public repository.

---

## 4. Limitations
- Injections where a host patch already performs the work of the handler are pruned only via `HOST_COVERS` rules (not automatic, ADR-0032). The functionality of pruned handlers may differ from standalone Fabric execution (alongside MinecraftForge: position-specific particles, in-wall sprites, automatic block item models).
- Because no release of Quilt Standard Libraries (QSL) exists for Minecraft 26.x, mods that need QSL modules are excluded before startup with an explicit reason (`PLEXUS-COMPAT-114` with `308`), and QSL entrypoints are not run (`303`). The exception: QSL entrypoints whose class implements Fabric's initializer interface run as Fabric entrypoints, and then a `quilt_base` dependency of that mod counts as met (`PLEXUS-COMPAT-311`).
- If a mod directly accesses private internal packages of Fabric Loader or Quilt Loader (such as `net.fabricmc.loader.impl.*`), it may be rejected during the link verification phase (`PLEXUS-COMPAT-111`).
