[English](README.md) | [한국어](README.ko.md)

# plexus-installer — Cross-Platform Installer & Launcher Integrator

`plexus-installer` is a cross-platform installer module for installing and managing Plexus Mod Loader in the official Minecraft launcher (`.minecraft`) and standalone server environments.

---

## 1. Distribution Format and Architecture

The Plexus installer is built and distributed as a single JAR across all platforms:

| Distribution Filename | Target Platform | Features |
| :--- | :--- | :--- |
| **`PlexusModLoader-<version>.jar`** | Windows, macOS, Linux (Java 17+) | Java Swing GUI-based cross-platform installer. Supports headless CLI installation. |

---

## 2. Key Features

1. **One-Click Official Minecraft Launcher Profile Creation:**
   - Safely adds a `Plexus Mod Loader <version>` profile to `.minecraft/versions` and `launcher_profiles.json`.
   - Performs atomic file writes and backups (`.plexusmodloader-backup`) to prevent corruption of existing configurations.
2. **Multilingual GUI Support (English / Korean):**
   - Automatically detects the system language and supports real-time switching via the language menu in the upper-right corner of the window.
3. **One-Click Standalone Server Installation (Command-Line `install-server`):**
   - Automatically configures the libraries, launch scripts, and mods folder (`mods/`) required to run a dedicated server in the specified directory.
4. **Automatic Optimal Memory Allocation Calculation:**
   - Detects physical system memory capacity and automatically configures the recommended JVM heap memory (`-Xmx`) in the profile.

---

## 3. Minecraft Versions Offered

The window, `--minecraft` and the release's `Minecraft:` line all use one list: the `installer.json` inside the jar, which the build takes from `gradle/minecraft-versions.properties` (every `supported` version and its hotfixes). Experimental versions are never offered. A version that is not in the list fails with `does not support Minecraft <version>; it supports ...` (exit code 1) before anything is downloaded or written.

### Versions before 26.1 (obfuscated, such as 1.20.1, 1.20.4, 1.21.1, 1.21.11)

Their game jars use short obfuscated names, so the installer remaps them to Mojang's names **on the player's computer** (ADR-0019), for both `install` and `install-server`:

1. It downloads Mojang's version data, the game jar and Mojang's own mappings (`client_mappings` or `server_mappings`) from the addresses in Mojang's version JSON, and checks each by the SHA-1 Mojang lists. A missing, damaged or changed file stops the install with a message; nothing of Plexus Mod Loader is written then.
2. It remaps the jar with ASM (about 5 to 15 seconds), checks the result (no obfuscated class name left, no duplicate member, `version.json` kept) and fails if that check fails.
3. It caches the result by the jar's SHA-1, the mappings' SHA-1 and the remapper revision, so installing again takes the cached jar.

Where the files go:

| Install | Mappings and remapped jar | How the game uses it |
| :--- | :--- | :--- |
| `install` (launcher) | `libraries/dev/plexusmodloader/minecraft-mojang/<version>/` of the launcher folder | The profile's version lists the remapped jar as a library and passes `-Dplexus.gameJar=${library_directory}/...`; it also asks the launcher for Java 25 (`javaVersion`). Mojang's own jar and version JSON are kept where the launcher keeps them (`versions/<version>/`), and the libraries the launcher downloads anyway are fetched into `libraries/`. |
| `install-server` | `versions/<version>/` of the server folder | `plexusmodloader-server.jar` lists the remapped jar on its class path instead of Mojang's jar. |

The mappings and the remapped jar are Minecraft code and Mojang's files: they are not part of the installer, a repository or a release, and `uninstall` removes the ones it made. Versions from 26.1 on are not obfuscated, need no downloads at install time and are installed as before.

The remap needs little memory (measured: a 1.21.11 client install completes with `java -Xmx256m -jar ...`). If the installer reports that it ran out of memory, start it with more, such as `java -Xmx2G -jar PlexusModLoader-<version>.jar`.

---

## 4. Build and Test (Gradle Tasks)

```powershell
# Run unit tests
.\gradlew.bat :plexus-installer:check

# Build distribution JAR (plexus-installer/build/distributions/)
.\gradlew.bat :plexus-installer:installerDistributions

# Mock launcher profile creation and launch integration test
.\gradlew.bat :plexus-bootstrap:launcherProfileTest

# Headless server installation and launch test (add -PserverInstallMinecraft=<version> for another offered version;
# versions before 26.1 run the phases that apply to them: remapped server up to the EULA check, no rejected rules)
.\gradlew.bat :plexus-installer:serverInstallTest
```

---

## 5. Module Structure

- **`dev.plexusmodloader.installer`**: Java Swing GUI window, CLI parser, profile JSON generator, library validator, and for versions before 26.1 the downloads (`VerifiedDownloads`), Mojang mappings parser (`MojangMappings`), ASM remapper (`MojangJarRemapper`) and its cache and check (`RemappedGameJar`, `ClientGameRemap`).
- Icons are drawn in code by `ProfileIcon`, and English and Korean text strings are located in `InstallerWindow.Language` (no separate resource files).
