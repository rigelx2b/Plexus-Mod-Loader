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

## 3. Build and Test (Gradle Tasks)

```powershell
# Run unit tests
.\gradlew.bat :plexus-installer:check

# Build distribution JAR (plexus-installer/build/distributions/)
.\gradlew.bat :plexus-installer:installerDistributions

# Mock launcher profile creation and launch integration test
.\gradlew.bat :plexus-bootstrap:launcherProfileTest

# Headless server installation and launch test
.\gradlew.bat :plexus-installer:serverInstallTest
```

---

## 4. Module Structure

- **`dev.plexusmodloader.installer`**: Java Swing GUI window, CLI parser, profile JSON generator, library validator.
- Icons are drawn in code by `ProfileIcon`, and English and Korean text strings are located in `InstallerWindow.Language` (no separate resource files).
