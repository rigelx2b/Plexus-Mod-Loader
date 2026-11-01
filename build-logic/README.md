[English](README.md) | [한국어](README.ko.md)

# build-logic — Gradle Conventions & Minecraft Tooling Plugins

`build-logic` is an internal build logic module (included build) that provides build conventions, code style verification, Minecraft asset provisioning, and Gradle plugins for mod development across the entire Plexus Mod Loader project.

---

## 1. List of Provided Plugins

| Plugin ID | Target | Description |
| :--- | :--- | :--- |
| **`plexus.java-conventions`** | All submodules | Java 25 toolchain configuration, UTF-8 encoding, and batch application of Spotless formatting rules |
| **`plexus.minecraft-dev`** | `plexus-bootstrap` | Downloads Minecraft client/server jars and libraries from Mojang servers, SHA-1 integrity verification, and execution/game test tasks (`runClient`, `serverBootSmokeTest`, etc.) |
| **`plexus.minecraft-compile`** | `plexus-game-*` | Attaches the Minecraft version jar and libraries matching the module name as `compileOnly` and compiles with that version's Java |
| **`plexus.minecraft-versions`** | Root project | `gradle/minecraft-versions.properties` inspection, `newMinecraftVersion`, `minecraftVersionDiff`, and `remapMinecraft` tasks |
| **`plexus.mod-template`** | Root project | New Plexus Mod project template generation (`newPlexusMod`) and distribution packaging |
| **`plexus.sdk-repository`** | Root project | Plexus SDK artifact build and local Maven publishing (`publishSdk`) |
| **`dev.plexusmodloader.mod`** (previous ID `dev.polymodloader.mod` is also the same plugin) | Mod projects (`templates/plexus-mod-template`) | Plexus Mod build: public API dependencies, `poly.mod.json` validation, resource generation |

---

## 2. Key Features and Pipelines

1. **Automated Minecraft Provisioning (`plexus.minecraft-dev`):**
   - Queries the official Mojang version manifest to automatically download the game client, server bundler, launcher assets, and external dependency libraries.
   - All files are verified with official SHA-1 hashes and cached in `~/.gradle/caches/plexus-minecraft` for reuse.
2. **Code Style Automation (`Spotless`):**
   - Integrates the palantir-java-format formatter and unused import removal.
   - Maintains a consistent style across the entire project codebase using the `.\gradlew spotlessApply` command.
3. **Mod Scaffolding Tools:**
   - `ModProjectScaffolder`: Generates skeleton code for new Plexus Mods.
   - `ModResourceGenerator`: Validates `plexus.mod.json` metadata and resource pack specifications.

---

## 3. Build and Test

```powershell
# Run unit tests for build-logic itself
.\gradlew.bat -p build-logic check
```
