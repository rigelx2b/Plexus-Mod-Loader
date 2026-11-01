[English](README.md) | [한국어](README.ko.md)

# Plexus Mod Loader Mod Template

A standalone Gradle project for starting a new Plexus Mod.

## Creating a New Mod

There are two ways to change the mod ID, name, Java package, entrypoint class, and project name all at once.

**Without the Plexus Mod Loader repository:** Download `plexus-mod-template-<version>.zip` from `https://www.plexusmc.net/maven/`, extract it, and run the following in that folder:

```powershell
.\gradlew.bat initPlexusMod -PmodId=ruby_mod "-PmodName=Ruby Mod" "-PmodPackage=io.github.you.ruby"
```

**From the Plexus Mod Loader repository:** Running this from the repository folder creates a new project next to the repository (or in `-PmodDir=<folder>`).

```powershell
.\gradlew.bat newPlexusMod -PmodId=ruby_mod "-PmodName=Ruby Mod" "-PmodPackage=io.github.you.ruby"
```

If `-PmodName` is omitted, it is generated from the mod ID; if `-PmodPackage` is omitted, it defaults to `com.example.<mod ID>`. You can also copy the folder directly. In that case, manually edit the items in "Where to Make Changes" below.

## Where Plexus Mod Loader is Downloaded From

`settings.gradle.kts` and the `dev.plexusmodloader.mod` plugin search for Plexus Mod Loader in the following order. No separate preparation is needed.

1. The website's public Maven repository `https://www.plexusmc.net/maven/` (only the `dev.plexusmodloader` group and the previous `dev.polymodloader` group)
2. Maven Local: Local builds published from the Plexus Mod Loader repository via `.\gradlew.bat publishToMavenLocal` and `.\gradlew.bat -p build-logic publishToMavenLocal`
3. Gradle Plugin Portal, Maven Central (other libraries)

Gradle uses the artifact from the first location found. To test an already published version with a local build, append `-PplexusLocalSdk` to the command to check Maven Local first. If the public repository cannot be reached, the build will fail, so use `--offline` when offline.

## Usage

```powershell
.\gradlew.bat build                  # Compile + validate plexus.mod.json + generate and check resources
.\gradlew.bat worldCreateSaveTest    # Load this mod on the game test server and create and save a world (headless)
.\gradlew.bat gameTests "-PplexusTests=my_mod:*"   # Run in-game tests for this mod (headless)
.\gradlew.bat runClient              # Run client (run/client)
```

In IntelliJ IDEA, open the folder as a Gradle project and run or debug the `.run/runClient` run configuration.

## Resource Files

Blocks and items specified in `plexusResources` in `build.gradle.kts` have their item definitions, models, blockstates, loot tables, English names, and tool tags generated at build time (`build/generated/plexus-resources/main`). That is why `ruby` and `ruby_block` in this template do not have JSON files. If you directly write a file at the same path in `src/main/resources`, that file will be used instead.

`build` uses `checkPlexusResources` to find and report uppercase file names, malformed JSON, missing models and textures, and omitted namespaces. The results are in `build/reports/plexus/resource-problems.txt`.

## Where to Make Changes

| File | Description |
|---|---|
| `src/main/resources/poly.mod.json` | Mod ID, name, version, entrypoints, dependencies |
| `src/main/java/com/example/mymod/MyMod.java` | Entrypoint (`CommonModInitializer`). Examples of 1 item, 1 block, and 2 game tests |
| `build.gradle.kts` | `plexusResources` (resources to generate), log lines to check in `worldCreateSaveTest` |
| `src/main/resources/assets/my_mod/` | Textures and manually written models and translations. Place data (recipes, loot tables, tags) in `data/my_mod/` |
| `settings.gradle.kts` | Project name |
| `gradle.properties` | `plexusMinecraft=<version>`: Minecraft version to use for running and testing (if absent, Plexus Mod Loader default version) |

`"minecraft"` in `plexus.mod.json` is the version the mod runs on. If you only use the Native API, writing something like `26.*` or `>=26.2 <27` allows it to be used on newer versions without rebuilding. Mods that rely on game code of a specific version should specify it exactly, such as `26.2`.

Available APIs can be found in the API Reference on the website (`https://www.plexusmc.net/docs/api-reference/`) (`docs/API_CATALOG.md` is not present in the public repository). IDEs also download the JavaDoc jar for public API modules from the Maven repository. All APIs are currently `PREVIEW`.
