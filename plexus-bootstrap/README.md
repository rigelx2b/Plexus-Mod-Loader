[English](README.md) | [한국어](README.ko.md)

# plexus-bootstrap — JVM Entrypoint & Execution Harness

`plexus-bootstrap` is the top-level **JVM entrypoint** for Plexus Mod Loader and the bootstrap module responsible for development environment execution and headless testing.

---

## 1. Key Roles and Responsibilities

1. **Provide the JVM main entrypoint:**
   - Owns `dev.plexusmodloader.bootstrap.PlexusLauncherMain`, the main class invoked first when launching Minecraft.
   - Reads system properties to construct the launch configuration (`LaunchConfiguration` in `plexus-runtime-impl`).
2. **Initialize the classloader hierarchy:**
   - The original Minecraft jar and mod jars are defined by the bytecode-transforming classloader (`GameClassLoader`) in `plexus-runtime-impl`.
   - Delegates actual mod loading and dependency resolution to `PlexusRuntime` in `plexus-runtime-impl`.
3. **Development environment execution tasks (Gradle Tasks):**
   - Provides Gradle tasks to immediately run the client, dedicated server, and game test server from IDEs and terminals.

---

## 2. Key Classes and Entrypoints

- **`dev.plexusmodloader.bootstrap.PlexusLauncherMain`**:
  - `main(String[] args)`: JVM entrypoint. Reads system properties and calls `new PlexusRuntime().launch(configuration, args)`.
- **`dev.plexusmodloader.runtime.LaunchConfiguration`** (`plexus-runtime-impl`):
  - Immutable launch configuration containing the launch target (client/server/game test server), game jar, mods directory, development mod jars, transformation dump/cache directories, report directory, etc.

### System Properties

| Property Name | Example Value | Description |
| :--- | :--- | :--- |
| `plexus.launchTarget` | `client`, `dedicated_server`, `game_test_server` | Target game environment to launch |
| `plexus.gameJar` | `/path/to/minecraft.jar` | Path to the vanilla Minecraft client/server jar |
| `plexus.modsDir` | `/path/to/mods` | Directory where user mods are located |
| `plexus.devModJars` | Comma-separated paths | List of mod jars under development |
| `plexus.transformDumpDir` | `/path/to/dumps` | Directory to dump class files for decompilation/inspection after bytecode transformation |
| `plexus.neoforge` | `on` / `off` | Whether NeoForge support is enabled (default: on) |
| `plexus.forge` | `auto` / `always` / `off` | Whether Forge support is enabled (default: auto) |

---

## 3. Development and Testing Tasks (Gradle Tasks)

```powershell
# 1. Client development run (launches Minecraft 26.2 development window)
.\gradlew.bat :plexus-bootstrap:runClient

# 2. Dedicated server development run (requires run/server/eula.txt agreement)
.\gradlew.bat :plexus-bootstrap:runServer

# 3. Headless server boot smoke test (for CI and quick verification)
.\gradlew.bat :plexus-bootstrap:serverBootSmokeTest

# 4. World creation, entity tick, and save integrity test
.\gradlew.bat :plexus-bootstrap:worldCreateSaveTest

# 5. In-game test execution (headless)
.\gradlew.bat :plexus-bootstrap:gameTests
```

Tasks 3 through 5 use mods from `examples/` and `test-mods/`, but because neither folder exists in this public repository, they halt with a message.

---

## 4. Dependency Structure

- **Internal projects:** `plexus-runtime-impl`, all supported versions of `plexus-version-adapter-v*`, `plexus-bridge-fabric`, `plexus-bridge-forge` (runtimeOnly)
- **External libraries:** `slf4j-api` (the logging implementation uses the log4j bundled with Minecraft)
