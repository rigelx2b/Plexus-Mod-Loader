[English](README.md) | [한국어](README.ko.md)

# plexus-transform-engine — High-Performance ASM Bytecode Transformation Engine

`plexus-transform-engine` is a high-performance ASM-based engine that parses, validates, transforms, and caches actual Minecraft class bytecode on disk based on declarative transformation rules defined in `plexus-transform-api`.

---

## 1. Two-Phase Transformation Architecture

```
[Transform Rules] ---> [ Phase 1: Planning (ClassTransformPlanner) ]
                             │  - Validate duplicate IDs and ordering dependencies
                             │  - Validate target class / method descriptors
                             │  - Check preconditions
                             ▼
                        [ TransformPlan ]
                             │
                             ▼
[Class Bytes]    ---> [ Phase 2: Execution (ClassTransformExecutor) ]
                             │  - Call-site redirection
                             │  - Inject return hooks & entry hooks
                             │  - Access Widener (unlock public/final)
                             │  - CheckClassAdapter integrity verification
                             ▼
                      [ Transformed Class Bytes ] ---> [ DirectoryTransformCache ]
```

### 1) Planning Phase (`ClassTransformPlanner`)
- Before the game starts, checks all registered transformation rules against the actual original class bytecode.
- Verifies duplicate IDs → supported versions and sides → target method existence → composability in advance to establish a finalized transformation plan (`TransformPlan`).

### 2) Execution and Verification Phase (`ClassTransformExecutor`)
- At the moment a class is actually loaded by the class loader, manipulates the ASM 9.11 tree according to the pre-established `TransformPlan`.
- Passes the bytecode through ASM's `CheckClassAdapter` immediately after manipulation to prevent the generation of invalid bytecode at the source.
- When `-Dplexus.transformDumpDir` is configured for debugging, automatically dumps pre- and post-transformation `.class` files to disk.

### 3) Disk Caching (`DirectoryTransformCache`)
- Creates a cache key (`TransformCacheKey`) for each class by hashing the engine fingerprint, class name, original class bytes, rules to apply, and access wideners with SHA-256.
- Already transformed classes are read immediately from the cache directory (default `.plexus/transform-cache`, changed via `-Dplexus.transformCacheDir`, disabled if `none`) to speed up subsequent boot times. The default limit is 256 MiB; when exceeded, the least recently used entries are evicted first.

---

## 2. Key Classes

| Class | Role |
| :--- | :--- |
| `dev.plexusmodloader.internal.transform.engine.ClassTransformPlanner` | Validates transformation rules and generates a unified execution plan (`TransformPlan`) |
| `dev.plexusmodloader.internal.transform.engine.ClassTransformExecutor` | Manipulates and verifies actual class bytecode based on ASM 9.11 |
| `dev.plexusmodloader.internal.transform.engine.DirectoryTransformCache` | Manages disk caching and LRU eviction of transformed bytecode |

---

## 3. Verification and Testing (Gradle Tasks)

```powershell
.\gradlew.bat :plexus-transform-engine:check
```

- **`ClassTransformPlannerTest`**: 13 tests covering standard transformations, static hook replacement, descriptor mismatches, ABORT policy, duplicate IDs, version filtering, etc.
- **`HookActionsTest`**: Verifies stack integrity by injecting entry and return hooks into classes compiled with javac and invoking them directly.
- **`CallSiteAndAccessTest`**: Verifies access modifier widening (`AccessWidening`) and call-site redirection ordering.
- **`TransformCacheTest`**: Verifies cache hits, invalidation, data corruption detection, and maximum capacity limit (256 MiB) handling.
