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
5. **Finding a Minecraft version fast (window):**
   - A search box above the version list narrows it as you type: `1.21` shows `1.21.11` and `1.21.1`, `26.x` shows every 26 version, `1.x` every version of Minecraft's `1.` line. Case, spaces and a leading `v` do not matter.
   - The list is grouped by series, newest first (`26.x`, `1.21.x`, `1.20.x`, ...). The series come from the version numbers; nothing is named in code, so a new version in `gradle/minecraft-versions.properties` appears in its series by itself.
   - The default version (the newest the installer offers) is selected at start and shown in bold with a "default" tag.
   - Keyboard only: type to search, Up/Down move through the versions (series headings are skipped, also in the list), Enter in the search box moves to the list, Escape clears the search, Tab goes on to the next control, Enter in the list installs.
   - When nothing matches, a line says so and how many versions the installer offers; Install then asks for a version first.
   - A click on a series heading folds or unfolds that series while the search is empty. The series of the chosen version stays open, so the choice is never hidden.
   - Below the list, the default version and up to three recently installed versions are one-click shortcuts (the recent ones are kept in the player's user preferences, after a successful install).
   - Switching the language keeps the search text and the chosen version.
   - The command line (`install`, `install-server`, `--minecraft`) behaves as before. For a version it does not support, the error line also suggests the supported versions it contains (`--minecraft 1.21` suggests `1.21.11, 1.21.1`, newest first); the exit code is still 1.

---

## 3. Minecraft Versions Offered

The window, `--minecraft` and the release's `Minecraft:` line all use one list: the `installer.json` inside the jar, which the build takes from `gradle/minecraft-versions.properties` (every `supported` version and its hotfixes). Experimental versions are never offered. A version that is not in the list fails with `does not support Minecraft <version>; it supports ...` (exit code 1) before anything is downloaded or written.

### Versions before 26.1 (obfuscated, such as 1.20.1, 1.20.4, 1.21.1, 1.21.11)

Their game jars use short obfuscated names, so the installer remaps them to Mojang's names **on the player's computer** (ADR-0019), for both `install` and `install-server`:

1. It downloads Mojang's version data, the game jar and Mojang's own mappings (`client_mappings` or `server_mappings`) from the addresses in Mojang's version JSON, and checks each by the SHA-1 Mojang lists. A missing, damaged or changed file stops the install with a message; nothing of Plexus Mod Loader is written then.
2. It remaps the jar with ASM (about 5 to 15 seconds), checks the result (no obfuscated class name left, no duplicate member, `version.json` kept) and fails if that check fails.
3. It caches the result by the jar's SHA-1, the mappings' SHA-1 and the remapper revision, so installing again takes the cached jar. The two SHA-1 values are the ones the download was just checked against, so the key does not hash the jar and the mappings a second and third time (the cache key text is unchanged, so earlier caches stay valid).

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
- **`VersionFilter`** (no Swing): orders the offered versions, groups them by series, decides what a search text matches and which version a search selects first. Unit-tested in `VersionFilterTest`.
- **`VersionPicker`** (Swing): the search box, the grouped list, the folding of series, the "nothing matches" line, the shortcut buttons and the keyboard handling, drawn from `VersionFilter` rows. Tested headless in `VersionPickerTest`.
- **`RecentVersions`**: the recently installed versions, parsed and updated without preferences (`parse`, `with`, `encode`, tested in `RecentVersionsTest`); the window stores them in `Preferences` after a successful install.
- Icons are drawn in code by `ProfileIcon`, and English and Korean text strings are located in `InstallerWindow.Language` (no separate resource files). `InstallerWindowTextTest` fails when a language lacks a string another one has: add every new string to all languages.
