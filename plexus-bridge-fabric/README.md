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

### 3-1) Intermediary Name Translation (Minecraft before 26.1, ADR-0037)
Fabric mods for 1.20.1, 1.20.4, 1.21.1 and 1.21.11 are built against FabricMC's *intermediary* names, while the game here runs in Mojang's names (ADR-0019). `IntermediaryTranslations.prepare(jar)` makes a copy of such a mod jar, once, on the player's computer, that names the game in Mojang's names:
- **Class files** (ASM `ClassRemapper`): class, field, method and record component names in declarations (a mod class that overrides a game method, a Mixin `@Shadow` member) and references, and every string that holds an intermediary name (`@Inject(method=...)`, `@At(target=...)`, `@Invoker`, `@Mixin(targets=...)`, constants).
- **Mixin reference maps** named by the mod's Mixin configurations, and **class tweaker files** in the `intermediary` namespace (rewritten to `official`).
- Inputs: Mojang's mappings next to the remapped game jar (kept by the installer or the build), and FabricMC's `intermediary-<minecraft>-v2.jar` (CC0-1.0), downloaded once into the same folder when missing. Copies are cached in `<reportDir>/translated-mods/<minecraft>/`. Nothing is uploaded or redistributed.
- Findings: `PLEXUS-COMPAT-142` (translated), `143` (mappings unavailable, blocks only mods that need them), `144` (failed), `145` (names left as written).
- `FabricLoader.getMappingResolver()` / `QuiltLoader.getMappingResolver()` answer for the `intermediary` namespace on these versions.
- The runtime reaches this through `ModLoaderBridge.prepareJar(Path)`.
- The parsed name tables (33 to 48 MB of heap) are read when a copy has to be made, and let go when the launch is prepared (`releaseTables()`) or after 30 seconds unused. A launch whose copies exist reads no mappings.

### 3-2) Fabric Loader Error Window Stand-ins
Some mods hand Fabric Loader's error window a status tree (ModernFix warns about an option another mod breaks). The bridge provides `net.fabricmc.loader.impl.gui.FabricGuiEntry` itself (no window: it writes the tree to the log and stops the game only when the mod asked for it with `exitAfter`) and packs `FabricStatusTree`, `FormattedException` and `Localization` from Fabric Loader, so such a mod is not left out with `PLEXUS-COMPAT-111`. `FabricBridge.providedPackages()` lists them; `ProvidedClassesTest` checks that every listed class is in the jar.

### 3-3) Mod jar file systems
`FabricModContainer` and `QuiltModContainer` give mods a path into the mod's jar (`getRootPaths()`, `rootPath()`, Quilt's `getSourcePaths()`). The zip file system behind it stays open for the rest of the game (mods keep the paths), so `SharedJarFileSystems` opens each jar once and hands every container the same one. Before, each Quilt container of a nested mod opened the outer jar again.

### 3-4) Reference Map Names in Host Checks
Fabric mods write their mixins in the names they were developed with (`method = "tryBreakBlock"`, an `@At` `target` of a yarn method) and let the reference map named by the Mixin configuration (`"refmap"`) turn them into the game's names; Mixin does that lookup when it applies the mixin. Alongside MinecraftForge or NeoForge, `HostPatchAdjustments` compares those strings with the game's classes before the patch and after it, so it needs the game's names too: with the yarn names it found no method, and every rule that works on a call or a field (`SAME_PLACE`, `IN_METHOD`) was left unapplied; only rules by handler name (`HOST_COVERS`) worked. `HostRefmapNames` now reads the reference map (`mappings`, then `data` for `named:intermediary`, by mixin class and string as Mixin does) and writes the names of the `method` and `target` strings of the injectors (and their nested `@At`/`@Slice`, MixinExtras' `@Definition`) into the mixin class, both when the mixin is checked and when it is loaded. A mod without a reference map, a string the map does not know, and anything marked `remap = false` (on the mixin, the injector or the `@At`) stay as written. Mixin finds none of the resolved strings in the map and uses them as they are. On Minecraft before 26.1 the map is the one the translation already rewrote to Mojang's names (3-1).

### 4) Entrypoint Lifecycle Management & Language Adapters
- **Language Adapters:** Supports custom language adapters declared in Fabric's `languageAdapters` and Quilt's `quilt_loader.language_adapters` (implementing `org.quiltmc.loader.api.LanguageAdapter` or `net.fabricmc.loader.api.LanguageAdapter`). Adapters from both loaders are unified in a single runtime registry, allowing cross-loader entrypoint instantiation. Unresolved adapters block the mod with `PLEXUS-COMPAT-312` (Quilt) or `PLEXUS-COMPAT-114` (Fabric).
- **`pre_launch` / `preLaunch`:** Called immediately after Mixin preparation and right before game initialization.
- **`main` / `client` / `server`:** Called at Minecraft internal initialization points according to each loader's interface contract.

---

## 2. Key Packages and Classes

| Package | Key Classes | Role |
| :--- | :--- | :--- |
| `dev.plexusmodloader.compat.fabric` | `FabricBridge`, `ClassTweakerFile`, `FabricClassTransformer` | Fabric mod discovery and transformation pipeline management |
| `dev.plexusmodloader.compat.fabric.mixin` | `FabricMixinBootstrap`, `PlexusMixinService`, `HostPatchAdjustments`, `HostRefmapNames`, `HostStaticCalls`, `HostTargetCalls`, `ReplacedReceiver`, `HostNeutralArguments` | Unified Mixin execution and automatic host patch adaptation. Bridges connecting `@WrapOperation`/`@Redirect` to calls modified by the host (static helpers, target class self-method invocations, neutral values for overloads with added arguments) |
| `dev.plexusmodloader.compat.fabric.intermediary` | `IntermediaryTranslations`, `IntermediaryTranslation`, `ModJarTranslator`, `IntermediaryRemapper`, `ClassTweakerTranslator` | Translation of intermediary-named mod jars to Mojang names on Minecraft before 26.1 (ADR-0037) |
| `dev.plexusmodloader.compat.fabric.loader` | `FabricLoaderRuntime`, `OfficialMappingResolver`, `SharedJarFileSystems` | Fabric Loader public API runtime implementation; one shared zip file system per mod jar |
| `net.fabricmc.loader.impl.*` | `FabricLoaderImpl`, `FabricLauncherBase`, `GameProvider`, `FabricGuiEntry` | Stand-ins of the few Fabric Loader internals mods use |
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
- If a mod directly accesses private internal packages of Fabric Loader or Quilt Loader (such as `net.fabricmc.loader.impl.*`), it may be rejected during the link verification phase (`PLEXUS-COMPAT-111`). The few internals mods really use are provided (`FabricLauncher`, `FabricLoaderImpl`, `GameProvider`, `FabricGuiEntry`, `FabricStatusTree`, `FormattedException`); `MappingConfiguration` is not (it hands out Fabric Loader's own mapping tree).
- The Mixin library keeps what it needs for later class loads (its `ClassInfo` cache and the class nodes of every Mixin class). The bridge does not hold a second copy and cannot release the library's without patching it; Fabric Loader keeps the same.
