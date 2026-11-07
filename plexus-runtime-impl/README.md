[English](README.md) | [한국어](README.ko.md)

# plexus-runtime-impl — Core Loading Pipeline & Runtime Coordinator

`plexus-runtime-impl` serves as the core brain of Plexus Mod Loader, governing the **entire 8-stage loading pipeline** from mod discovery to dependency resolution, cross-loader conflict analysis, classloader configuration, and game launch.

---

## 1. 8-Stage Runtime Lifecycle Pipeline

```
1. [Launch Config]       -> Finalize CLI arguments, JVM properties, and mod folder path
          │
          ▼
2. [Mod Discovery]       -> Discover jars and nested jars in mods/ directory
          │
          ▼
3. [Metadata Parsing]    -> Parse plexus, fabric, quilt, forge, and neoforge metadata
          │
          ▼
4. [Dependency Solver]   -> Verify version constraints, required dependencies, and circular references
          │
          ▼
5. [Cross-Loader Compat] -> HostPatchCheck (inspect host patch conflicts and missing methods)
          │
          ▼
6. [Transform Planning]  -> Plan ASM bytecode transformations and generate disk cache
          │
          ▼
7. [GameClassLoader]     -> Initialize TransformingClassLoader and FML/Fabric bridges
          │
          ▼
8. [Entrypoints & Launch]-> Sequentially execute per-loader entrypoints (Pre-launch, Main, etc.) and launch game
```

---

## 2. Key Packages and Components

| Package | Key Classes | Role |
| :--- | :--- | :--- |
| `dev.plexusmodloader.runtime` | `PlexusRuntime`, `LaunchConfiguration` | Runtime boot orchestration and state management |
| `dev.plexusmodloader.runtime` | `LoadedModCounter` | Real-time per-loader mod count aggregation |
| `.discovery` | `ModFileScanner`, `NestedJarExtractor`, `ForeignModResolver`, `FabricApiProvision`, `QuiltedFabricApiProvision` | Discover jars in mod folder, extract nested jars, identify loaders, and download pinned Fabric API and Quilted Fabric API (ADR-0046, ADR-0048) |
| `.dependency` | `ModDependencyResolver` | Resolve mod version constraints and sort load priority |
| `.compat` | `HostPatchCheck`, `CrossLoaderModResolver`, `HostFinalOverrides` | Pre-diagnose conflicts between Forge/NeoForge patches and Fabric Mixins, and permit reviewed host-final method overrides for Fabric/Quilt classes (ADR-0047) |
| `.classloading` | `GameClassLoader`, `GameLibraries` | Manage the single game classloader, hide unused loader APIs (`hideFromParent`), and supply classpath libraries with Minecraft jars excluded |
| `.lifecycle` | `EntrypointInvoker`, `ShutdownCoordinator` | Invoke entrypoints and execute shutdown tasks upon JVM termination |

---

## 3. Key Technical Features

### 1) Unused Loader API Isolation (`hideFromParent`)
When a modpack contains no Fabric mods and only Forge mods, this prevents multi-loader mods from misidentifying the loader environment due to leftover `net.fabricmc.api.*` classes on the classpath. Plexus selectively masks the bridge packages of loaders without active mods on the `GameClassLoader`.

### 2) Real-Time Per-Loader Mod Count Aggregation (`LoadedModCounter`)
To supply accurate mod counts to game loading and title screens, as well as the custom loading screen (`PlexusLoadingOverlay`), it calculates the actual running mod count for each loader (`Plexus`, `Fabric`, `Forge`, `NeoForge`, `Quilt`) in real time without duplicates.

### 3) Pre-Link Verification (`ModLinkVerifier`)
By inspecting the constant pools of candidate mods to be loaded, it identifies fatal mods that reference methods or fields missing in Minecraft or upstream libraries before boot, preventing crashes in advance.

---

## 4. Verification and Testing (Gradle Tasks)

```powershell
.\gradlew.bat :plexus-runtime-impl:check
```

- `ModDependencyResolverTest`: 12 tests verifying complex dependency trees, circular dependencies, and optional dependencies.
- `CrossLoaderModResolverTest`: Verification of ID mapping and bridge deduplication when Forge + Fabric mods coexist.
- `ModLinkVerifierTest`: 14 tests verifying class bytecode linkage integrity.
- `PlexusRuntimeTest`: End-to-end boot verification across the entire 1–8 stage pipeline using synthetic jars.
