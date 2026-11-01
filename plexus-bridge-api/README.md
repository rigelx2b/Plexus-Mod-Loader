[English](README.md) | [한국어](README.ko.md)

# plexus-bridge-api — Cross-Loader Compatibility SPI

`plexus-bridge-api` is the module that defines the **cross-loader bridge service provider interface (SPI)** and diagnostic data models for integrating various third-party mod loaders such as Fabric, Forge, NeoForge, and Quilt.

---

## 1. Key Interfaces and Contracts

### `ModLoaderBridge` (Core SPI)
The interface implemented by each loader bridge (`plexus-bridge-fabric`, `plexus-bridge-forge`).
- **`assess(metadata, side, ModJarContents)`**: Pre-analyzes metadata (and files within the jar referenced by the metadata) and returns compatibility diagnostic results (`BridgeAssessment`).
- **`runtimePackages()`**: Reports the list of runtime API packages for the loader (subject to being hidden when unused).
- **`gameHost(GameHost.Request, modJars)`**: Creates the `GameHost` for a host loader (Forge/NeoForge) that directly patches and launches the game.
- **`hostIncompatibilities(...)`**: Reports mods from this bridge that cannot run as-is on the host-patched game, along with the reasons (`PLEXUS-COMPAT-224`).

### `InjectionPointReplacement`
An immutable data model for replacing at runtime vanilla method injection points that have moved or changed due to Forge or NeoForge bytecode patches.
- `targetClass`: Target vanilla class (e.g., `net/minecraft/locale/Language`)
- `method`: Target method name (e.g., `loadDefault`)
- `replaced`: Original injection target removed by the patch (invocation or field)
- `replacement`: Target that replaces it
- `placement`: Replacement strategy (`SAME_PLACE`, `AFTER_TRUE_RESULT`, `IN_METHOD`, `HOST_COVERS`, `HOST_NEUTRAL_ARGUMENT`, etc.). `HOST_COVERS` is a rule where the host's own code already performs the work of a single injection handler, so only that handler is stripped out and the mod is loaded (`replaced` is `<internal mixin name>#<handler>`), and `HOST_NEUTRAL_ARGUMENT` is a rule where a `@Redirect` on an invocation that the host replaced with an overload adding an argument is executed only when the host passes a neutral value (`replacement` is `<invocation>#<owner>;<field>`).

### Diagnostic Data Models
- **`CompatibilityStatus`**: `VERIFIED` (passed test plan), `PARTIAL` (known feature limitations), `EXPERIMENTAL` (path verified only through limited experiments), `BLOCKED` (unrunnable, not loaded), `UNTESTED` (not evaluated). The first three are loaded.
- **`CompatibilityFinding`**: Encapsulates a structured error code (`PLEXUS-COMPAT-...`) and the specific conflict reason.

---

## 2. Verification and Build

```powershell
.\gradlew.bat :plexus-bridge-api:check
```
