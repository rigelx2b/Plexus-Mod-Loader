[English](README.md) | [한국어](README.ko.md)

<p align="center"><img src="logo.svg" alt="Plexus Mod Loader" width="96" height="96"></p>

# Plexus Mod Loader

Plexus Mod Loader is a mod loader for Minecraft: Java Edition (version 1.10.0, stable). It installs as a profile in the official Minecraft Launcher without external launcher wrappers, starts the unmodified vanilla game files through itself, and loads Fabric, Quilt, Forge, and NeoForge mods alongside its own Plexus Mods written against its Native API. Forge and NeoForge run as themselves: Plexus Mod Loader starts the real Forge and FML, or the real NeoForge and FML 11, downloading their files automatically the first time such mods are present. Before the game starts, it checks every mod; any mod that cannot run is left out with a `PLEXUS-*` error code in `logs/latest.log` and a notice on the title screen rather than crashing later. Download the installer from [plexusmc.net/download](https://www.plexusmc.net/download/) or [GitHub Releases](https://github.com/xyrz47-spec/Plexus-Mod-Loader/releases).

## Status

Current version: **1.10.0** (stable). Supported Minecraft versions: **26.1**, **26.1.1**, **26.1.2**, **26.2**, and **26.3** (default **26.3**). The game uses **Java 25**.

| Mod type / combination | Status | Details and limits |
| :--- | :--- | :--- |
| Plexus Mods | Verified | Mods written against the Native API. |
| Fabric mods | Verified | Entry points, Fabric Loader API, nested JARs, Mixin, MixinExtras, and class tweakers. For Fabric Loader internal classes, only what real mods use is provided. |
| Quilt mods | Verified | `quilt.mod.json`, Quilt Loader API, Mixin, and access wideners. Mods that need Quilt Standard Libraries (QSL) are left out because QSL has no release for these versions. |
| NeoForge mods | Verified | Runs NeoForge's own FML 11 (`26.3.0.98` on 26.3, `26.2.0.93` on 26.2). |
| Forge mods | Verified | Runs Forge itself (`66.1.0` on 26.3, `65.1.5` on 26.2). |
| Fabric or Quilt next to Forge or NeoForge | Partial | A Fabric or Quilt mod whose mixins do not fit the patched game is left out (`PLEXUS-COMPAT-224`), and so are mods that need it. |

- **Mixing:** Fabric and Quilt mods run together and next to Plexus Mods. Forge mods and NeoForge mods cannot share one game: each patches the game's classes in its own way (`PLEXUS-COMPAT-221`).
- **What `Verified` means:** Run, not just loaded. Real popular mods from Modrinth enter a saved world on the client, tick, save and quit, and start a dedicated server. Compatibility claims come from those runs: [compatibility reference](https://www.plexusmc.net/docs/reference/compatibility/).

## Install

One installer JAR supports Windows, macOS, and Linux. Running the installer requires **Java 17 or later**.

1. Download `PlexusModLoader-<version>.jar` from [plexusmc.net/download](https://www.plexusmc.net/download/) or [GitHub Releases](https://github.com/xyrz47-spec/Plexus-Mod-Loader/releases).
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
git clone https://github.com/xyrz47-spec/Plexus-Mod-Loader.git
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

Licensed under the **Mozilla Public License 2.0** (see [`LICENSE`](LICENSE)). Copyright 2026 _xyrz and the Plexus Mod Loader contributors.

- You may use, study, modify, and redistribute Plexus Mod Loader for any purpose, including commercial use. If you distribute a modified version, the MPL files you changed stay under MPL 2.0 and you must make their source available; your own new files can use any license.
- Mods, modpacks, and launchers that use Plexus Mod Loader choose their own license. `templates/` is under MIT No Attribution (see [`templates/LICENSE`](templates/LICENSE)).
- The license does not cover the names "Plexus" and "Plexus Mod Loader" or the logo: a modified version needs its own name (see [`TRADEMARKS.md`](TRADEMARKS.md)).
- Third-party components: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.