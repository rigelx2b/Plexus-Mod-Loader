[English](README.md) | [한국어](README.ko.md)

<p align="center"><img src="logo.svg" alt="Plexus Mod Loader" width="96" height="96"></p>

# Plexus Mod Loader

Plexus Mod Loader is a mod loader for Minecraft: Java Edition (version 2.1.6, stable). It installs as a profile in the official Minecraft Launcher without external launcher wrappers, starts the unmodified vanilla game files through itself, and loads Fabric, Quilt, Forge, and NeoForge mods alongside its own Plexus Mods written against its Native API. Forge and NeoForge run as themselves: Plexus Mod Loader starts the real Forge and FML, or the real NeoForge and FML 11, downloading their files automatically the first time such mods are present. Before the game starts, it checks every mod; any mod that cannot run is left out with a `PLEXUS-*` error code in `logs/latest.log` and a notice on the title screen rather than crashing later. Download the installer from [plexusmc.net/download](https://www.plexusmc.net/download/) or [GitHub Releases](https://github.com/rigelx2b/Plexus-Mod-Loader/releases).

## Status

Current version: **2.1.6** (stable). Supported Minecraft versions: **1.20.1**, **1.20.4**, **1.21.1**, **1.21.11**, **26.1**, **26.1.1**, **26.1.2**, **26.2**, and **26.3** (default **26.3**). The game uses **Java 25**. Pre-26.1 obfuscated versions remap vanilla game jars on demand on the player's machine using official Mojang mappings without redistributing mappings or jars.

| Mod type / combination | Status | Details and limits |
| :--- | :--- | :--- |
| Plexus Mods | Verified | Mods written against the Native API. |
| Fabric mods | Verified | Entry points, Fabric Loader API, nested JARs, Mixin, MixinExtras, and class tweakers. For Fabric Loader internal classes, only what real mods use is provided. |
| Quilt mods | Verified | `quilt.mod.json`, Quilt Loader API, Mixin, and access wideners. On 1.20.1 to 1.21.11, Quilt mods run on servers with bundled Quilt Config (2.1.2). On 1.20.1, 1.20.4, and 1.21.1, Quilt Standard Libraries (QSL) / Quilted Fabric API run with automatic first-launch Quilted Fabric API download when an installed Quilt mod needs a QSL module (`PLEXUS-DISCOVERY-006`/`007`, `-Dplexus.qsl=off` turns it off, ADR-0048, 2.1.3); on other supported versions, mods that need QSL are left out because QSL has releases only for 1.20.1, 1.20.4, and 1.21.1 (`PLEXUS-COMPAT-303`/`308`/`311`). Since 2.1.6, Quilt mods also reach a world in client game windows on 1.20.1, 1.20.4, 1.21.1, and 1.21.11 (loaded / not loaded: 1.20.1 261 / 790 with QSL left out, 1.20.4 215 / 64 with QSL, 1.21.1 146 / 132 with QSL, 1.21.11 143 / 124). |
| NeoForge mods | Verified | Runs NeoForge's own FML 11 (`26.3.0.98` on 26.3, `26.2.0.93` on 26.2). On 1.20.4, 1.21.1 and 1.21.11 it runs NeoForge `20.4.253`, `21.1.265` and `21.11.48` (headless, 2.1.0; client windows verified, 2.1.3). There is no NeoForge for 1.20.1 (`PLEXUS-COMPAT-216`). |
| Forge mods | Verified | Runs Forge itself (`66.1.0` on 26.3, `65.1.5` on 26.2). On 1.20.1, 1.20.4, 1.21.1 and 1.21.11 it runs Forge `47.4.28`, `49.2.13`, `52.1.21` and `61.2.5` (headless, 2.1.0; client windows verified, 2.1.3). |
| Fabric or Quilt next to Forge or NeoForge | Partial | A Fabric or Quilt mod whose mixins do not fit the patched game is left out (`PLEXUS-COMPAT-224`), and so are mods that need it. Before 26.1, Fabric API runs next to Forge on 1.20.1 and 1.20.4 by opening reviewed host-final methods (`PLEXUS-COMPAT-228`, ADR-0047), and on 1.20.1 Quilt mods run next to Forge (`corpusQuiltForge`, 110 mods loaded, 2.1.5). Fabric and Quilt mods run headless in the same game as NeoForge `21.1.265` (1.21.1) and `20.4.253` (1.20.4) with one shared Mixin (2.1.3; loaded/not loaded: `corpusFabricNeoForge` 15/40 on 1.21.1 and 57/80 on 1.20.4, `corpusFabricOnNeoForge` 69/88 and 65/89, `corpusQuiltNeoForge` 72/194 and 77/179; limits: with Forgified Fabric API on 1.21.1 Fabric API's own build is left out); Fabric mods next to NeoForge 21.1.265 (2.1.4) and 20.4.253 (2.1.5) pass in a client window (`corpusFabricNeoForgeClient`, reaching the world and leaving after 200 ticks). |

- **Mixing:** Fabric and Quilt mods run together and next to Plexus Mods. Forge mods and NeoForge mods cannot share one game: each patches the game's classes in its own way (`PLEXUS-COMPAT-221`).
- **What `Verified` means:** Run, not just loaded. Real popular mods from Modrinth enter a saved world on the client, tick, save and quit, and start a dedicated server. Compatibility claims come from those runs: [compatibility reference](https://www.plexusmc.net/docs/reference/compatibility/).
- **Pre-26.1 versions (1.20.1, 1.20.4, 1.21.1, 1.21.11):** Plexus Mods run there. Fabric and Quilt mods are translated once, on the player's computer, from intermediary names to Mojang names (2.1.0), so most of the popular ones load; mods that still do not fit are left out with a reason. Forge and NeoForge mods run on their own loaders there (2.1.0; see the rows above). These runs were headless (a game test server, a dedicated server, and real Modrinth mod sets); for Forge and NeoForge client windows before 26.1, `corpusForgeClient` (1.20.1, 1.20.4, 1.21.1, and 1.21.11) and `corpusNeoForgeClient` (1.20.4, 1.21.1, and 1.21.11) reach the world, run 200 ticks, and leave (2.1.3; installed Forge and NeoForge launcher profiles are verified on 1.20.1, 1.20.4, 1.21.1, and 1.21.11, with NeoForge on 1.20.4, 1.21.1, and 1.21.11, 2.1.4).

## Install

One installer JAR supports Windows, macOS, and Linux. Running the installer requires **Java 17 or later**.

1. Download `PlexusModLoader-<version>.jar` from [plexusmc.net/download](https://www.plexusmc.net/download/) or [GitHub Releases](https://github.com/rigelx2b/Plexus-Mod-Loader/releases).
2. Run vanilla Minecraft once in the Launcher in the version you want, then close the Launcher.
3. Double-click the JAR or run `java -jar PlexusModLoader-<version>.jar`. Pick the Minecraft version and the launcher folder, then click **Install**.
4. In the Launcher, choose the profile `Plexus Mod Loader <Minecraft version>` and play. Put mods in the launcher folder's `mods/` directory.

**Dedicated server:** Run `java -jar PlexusModLoader-<version>.jar install-server`, then run `java -jar plexusmodloader-server.jar` in the server folder. You accept the Minecraft EULA yourself.

Full guide: [Installation Guide](https://www.plexusmc.net/docs/getting-started/install/).

## Make mods

Plexus Mods use **Java 25** and **Gradle** (**JDK 25** is needed). Gradle downloads Minecraft from Mojang and checks its hash. Start from [`templates/plexus-mod-template`](templates/plexus-mod-template) in this repository (`MIT-0` license; see [`templates/LICENSE`](templates/LICENSE)), which includes the Gradle Wrapper.

The public API exposes no Minecraft classes. The Native API covers:

- Blocks, items, mobs, and effects; block entities and menus
- World generation and dimensions
- Commands and permissions; networking and join checks; saved data and sync
- HUD, screen, and shader drawing
- In-game tests, game system replacement, and declarative bytecode transforms

Documentation: [First Mod Tutorial](https://www.plexusmc.net/docs/getting-started/first-mod/) | [Native API Guide](https://www.plexusmc.net/docs/native-api/) | [API Reference](https://www.plexusmc.net/docs/api-reference/)

## Build from source

Requires **JDK 25** and **Git**. The build uses **Gradle Wrapper 9.8.1** (Kotlin DSL). The first build downloads Minecraft from Mojang and verifies its SHA-1; game JARs stay in the Gradle cache and are never committed.

```sh
git clone https://github.com/rigelx2b/Plexus-Mod-Loader.git
cd Plexus-Mod-Loader
```

| Command (Windows; use `./gradlew` on macOS and Linux) | Purpose |
| :--- | :--- |
| `.\gradlew.bat check` | Compile, format check, unit and integration tests |
| `.\gradlew.bat :plexus-installer:installerDistributions` | Build `plexus-installer/build/distributions/PlexusModLoader-<version>.jar` |
| `.\gradlew.bat :plexus-bootstrap:runClient` | Run the development client |
| `.\gradlew.bat :plexus-bootstrap:serverBootSmokeTest` | Boot a real server headless (network needed) |

The public repository holds the loader's source and build. The website, design documents, example mods, test mods, and real-mod corpus lists are kept out of it, and the build works without them.

## Repository layout

| Path / pattern | Responsibility |
| :--- | :--- |
| `plexus-bootstrap` | Entry point, launch, and verification Gradle tasks |
| `plexus-runtime-api`, `plexus-runtime-impl` | Mod discovery, dependency resolution, and runtime lifecycle |
| `plexus-mod-metadata` | `plexus.mod.json` parsing |
| `plexus-transform-api`, `plexus-transform-engine` | Declarative bytecode transformation |
| `plexus-bridge-api`, `plexus-bridge-fabric`, `plexus-bridge-forge` | Bridges that run Fabric/Quilt and Forge/NeoForge mods |
| `plexus-fabric-api-forge-v*`, `plexus-fabric-api-neoforge-v*` | Fabric API modules adapted to run next to Forge and NeoForge |
| `plexus-game-api`, `plexus-event-api`, `plexus-lifecycle-api`, `plexus-system-api`, `plexus-system-registry` | The Native API and replaceable systems |
| `plexus-version-adapter-api`, `plexus-version-adapter-v<id>`, `plexus-game-v<id>` | Per-Minecraft-version adapters and game layers (the only code naming Minecraft classes) |
| `plexus-early-window` | The early loading window |
| `plexus-installer` | The player installer |
| `build-logic` | Gradle convention plugins and tasks |
| `templates/plexus-mod-template` | Mod project template |

Every module has its own README in English and Korean.

## Documentation

- Website and documentation: [plexusmc.net/docs](https://www.plexusmc.net/docs/)
- Troubleshooting: [Troubleshooting Guide](https://www.plexusmc.net/docs/getting-started/troubleshooting/)
- Repository documents: [Project status (`PROJECT.md`)](PROJECT.md), [Architecture (`ARCHITECTURE.md`)](ARCHITECTURE.md), [Developer guide (`DEVELOPING.md`)](DEVELOPING.md), [Changelog (`CHANGELOG.md`)](CHANGELOG.md)
- Measured performance: [Performance Reference](https://www.plexusmc.net/docs/reference/performance/). Performance numbers are published there along with measurement methods; the project states performance only from measurements.

## License

Licensed under the **MIT License** (see [`LICENSE`](LICENSE)). Copyright 2026 rigelx2b and the Plexus Mod Loader contributors.

- You may use, copy, modify, merge, publish, distribute, sublicense, and sell Plexus Mod Loader for any purpose, including in commercial or closed-source works, provided the copyright and license notice are kept in all copies or substantial portions. Releases after 1.1.0 up to 2.0.0 remain under MPL-2.0.
- Mods, modpacks, and launchers that use Plexus Mod Loader choose their own license. `templates/` is under MIT No Attribution (see [`templates/LICENSE`](templates/LICENSE)).
- The license does not cover the names "Plexus" and "Plexus Mod Loader" or the logo: a modified version needs its own name (see [`TRADEMARKS.md`](TRADEMARKS.md)).
- Third-party components: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.