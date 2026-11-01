[English](README.md) | [한국어](README.ko.md)

# plexus-runtime-api — Public Runtime Contracts & Stability Markers

`plexus-runtime-api` is a pure Java base module that defines the top-level contracts and stability markers that all Plexus Mod Loader public APIs and Plexus Mods depend on in common.

---

## 1. Key Roles and Design Principles

1. **Zero External Dependencies:**
   - Does not depend on any vanilla Minecraft classes or third-party libraries (ASM, Gson, etc.).
   - Composed solely of JDK 25 base runtime types, allowing safe reference across all layers.
2. **Explicit API Stability Levels (`@ApiStability`):**
   - Specified on all public interfaces and classes in Plexus to notify mod developers of potential API changes in advance.
   - Automatically displayed in JavaDoc, IDE documentation tooltips, and runtime diagnostic tools.

---

## 2. Key Types

### `@ApiStability` & `StabilityLevel`
- **`STABLE`**: A stable API whose backward compatibility is strictly guaranteed until a Semantic Versioning (SemVer) major bump.
- **`PREVIEW`**: An API whose functional behavior has been verified, but whose signature or contract may be refined based on community feedback.
- **`VERSION_BOUND`**: An API tied to a specific Minecraft version and accessible only through a version adapter.
- **`INTERNAL`**: Dedicated to the internal Plexus Mod Loader engine. An API for which direct calls by external mod developers are discouraged.

### `GameSide`
- `CLIENT`: Game client. The singleplayer integrated server also runs in this process.
- `DEDICATED_SERVER`: Dedicated server with no client classes.

---

## 3. Verification and Build

```powershell
.\gradlew.bat :plexus-runtime-api:check
```
