[English](README.md) | [한국어](README.ko.md)

# plexus-fabric-api-forge-v26_2 — Fabric API Host Module for MinecraftForge 26.2

For Fabric API modules that cannot run normally alongside MinecraftForge even with Plexus Mod Loader's Mixin bridge, this module builds dedicated Fabric API implementations ("host modules", `ADR-0031`) operating on top of `MinecraftForge 26.2-65.1.5` hooks. The NeoForge version is [`plexus-fabric-api-neoforge-v26_2`](../plexus-fabric-api-neoforge-v26_2/README.md), and the 26.3 version is [`plexus-fabric-api-forge-v26_3`](../plexus-fabric-api-forge-v26_3/README.md).

## What this module does
- Builds three modules with the same public API as their counterparts in Fabric API `0.161.0+26.2`: `fabric-biome-api-v1` (`18.0.6+plexus.forge.26.2`, marked `always` to replace the original even if loaded), `fabric-content-registries-v0` (`11.3.2+plexus.forge.26.2`), and `fabric-rendering-fluids-v1` (`6.0.4+plexus.forge.26.2`, `always`).
- Sourced from Fabric API's own source (`FabricMC`, https://github.com/FabricMC/fabric). Without passing through intermediate ports like Forgified Fabric API, the header of each file documents the changes made relative to the Fabric API original. As an exception, `fabric-rendering-fluids-v1` is sourced from a build of Forgified Fabric API (the same code already included for NeoForge).
- `fabric-biome-api-v1`: Replaced because although the original mixins target Forge patches, the code does not match.
  - The original accesses `ChunkGenerator.featuresPerStep` using a `Supplier`, but Forge changed this field to `ClearableLazy` (a link check reading the patched game jar first drops the original with `PLEXUS-COMPAT-112`). This build avoids using the field and instead calls `ChunkGenerator.refreshFeaturesPerStep()`, added by Forge.
  - Forge's `Biome` reads generation and spawn settings from `ModifiableBiomeInfo`, and codecs (`DIRECT_CODEC`, `NETWORK_CODEC`) use the climate and effects contained in the original copy of that information. Fabric API modifies generation and spawn settings and effects in place inside the `MinecraftServer` constructor (before Forge applies its modifiers), and modifies climate by mutating the biome's fields. Consequently, biomes passed through codecs or sent to the client lose their climate changes. `ForgeBiomeInfo` attaches a new `ModifiableBiomeInfo` to the biome with the Fabric API-modified biome as its original, ensuring that Forge's modifiers start on top of it and codecs use Fabric API's climate. Without this, `forgeFabricApiGameTest` reads Plains temperature as 0.8 instead of 1.25.
- `fabric-content-registries-v0`: Forge moved drowning and fluid jumping to `FluidType` hooks (`PLEXUS-COMPAT-224`), leaving nowhere to attach the original mixins.
  - Removes `customFluidDrowning` and `swapFluidTag` from Fabric API's `LivingEntityMixin` (Forge changed the targeted code).
  - Drowning is handled by `ForgeFluidHooks` (Fabric `main` entry point) attaching a listener to Forge's `LivingBreatheEvent`. It prevents breathing when eyes are submerged in a drowning Fabric fluid, while excluding bubble columns, underwater-breathable entities, the Water Breathing effect, and invulnerable players, consistent with vanilla behavior.
  - `FluidMixin` ensures that `getFluidType()` for fluids unknown to Forge (e.g., fluids registered by Fabric mods) returns one of water, lava, or empty `FluidType`. Forge's default lookup throws `"Mod fluids must override getFluidType."` for such fluids.
  - In the 26.2 edition, `ForgeTillingHooks` (Fabric `main` entry point) executes `TillableBlockRegistry` via Forge's `BlockToolModificationEvent`. This is because Forge's `HoeItem` calls `getToolModifiedState(HOE_TILL)` instead of reading `TILLABLES`.
- `fabric-rendering-fluids-v1`: Forge (`26.2-65.1.5`, `javap`) replaced the vanilla overlay check in `FluidRenderer.tesselate` (`relativeBlock instanceof HalfTransparentBlock || instanceof LeavesBlock`) with `BlockState.shouldDisplayFluidOverlay(BlockAndTintGetter, BlockPos, FluidState)` (NeoForge's version takes `BlockAndLightGetter`). The original `FluidRendererMixin` (`modifyNonOverlayCheck`, MixinExtras expression `? instanceof HalfTransparentBlock`) fails to find the target in Forge's `FluidRenderer`, crashing the game when rendering the first chunk with `PLEXUS-COMPAT-225` ("Critical injection failure ... Scanned 0 target(s)"), which pre-execution checks do not foresee as they do not inspect expression injection points. This build aligns the overlay check with Forge's `shouldDisplayFluidOverlay` call. `FluidRenderingRegistryImpl.isBlockTransparent(block, Forge's response)` preserves Forge's response if no Fabric mod has registered that block.
- Licensed under `Apache License 2.0`, and each module jar contains a `LICENSE-<module ID>` file.

## What it owns
- Source sets `src/fabricBiomeApiV1`, `src/fabricContentRegistriesV0`, and `src/fabricRenderingFluidsV1`, along with each module's `fabric.mod.json`, Mixin configuration, and class tweaker.
- The `custom` field `plexusmodloader:host-module` in `fabric.mod.json` (`host` `minecraftforge`, `minecraft` `26.2`, `replaces` each module version).
- Build artifact jars (`META-INF/plexusmodloader/host-modules/minecraftforge-26.2-65.1.5/`) and the index for these modules (including `always` markers). `plexus-bridge-fabric` merges these with indices from other host modules into a single jar entry (`hostModuleIndex` task).
- The consumable configuration `hostModuleElements` that provides artifacts to `plexus-bridge-fabric`.

## What it does not do
- Does nothing when MinecraftForge is absent or on releases other than `26.2-65.1.5`.
- Not intended for NeoForge.
- Does not download or require Forgified Fabric API.
- Does not reimplement Fabric API modules that already run via the bridge.
- Does not place jars directly onto Plexus Mod Loader's classpath.

## Dependencies
- Minecraft `26.2` (`plexus.minecraft-compile`, pinned to `plexusCompileMinecraft=26.2` in the module's `gradle.properties`).
- Forge's patched client jar: based on `libraries/net/minecraftforge/forge/26.2-65.1.5/forge-26.2-65.1.5-client.jar`, generated by running the `MinecraftForge 26.2-65.1.5` installer (verified with SHA-1 `0746b1dc6d93abb4b84f3f924009591b6c85eb80`) with `--installClient` into `~/.gradle/caches/plexus-forge/26.2-65.1.5/client` via the `installForgeClient` task (`ForgeClientInstallTask`, network required on first use). Because this jar also contains common classes, it is placed before the vanilla jar.
- The `ClassTweakedJarTask` (`<sourceSet>CompileGame`) task applies the module's class tweakers to this jar at compile time only.
- `compileOnly`: Forge universal jar, Forge's event bus (for `LivingBreatheEvent` listeners), Forge mergetool API (`Dist`, `OnlyIn`), Fabric Loader, Mixin, MixinExtras, ASM, `fabric-api-base`, `fabric-resource-loader-v1`, `fabric-lifecycle-events-v1`, jspecify, JetBrains annotations.

## Key entry points
- `fabric-biome-api-v1` public API: `net.fabricmc.fabric.api.biome.v1` (`BiomeModifications`, `BiomeSelectors`, `NetherBiomes`, `TheEndBiomes`, etc.).
- `fabric-content-registries-v0` public API: `net.fabricmc.fabric.api.registry` (`FlammableBlockRegistry`, `StrippableBlockRegistry`, `TillableBlockRegistry`, `OxidizableBlocksRegistry`, etc.), `net.fabricmc.fabric.api.registry.fluid`.
- `fabric-rendering-fluids-v1` public API (client): `net.fabricmc.fabric.api.client.render.fluid.v1`.
- Forge integration: `ForgeBiomeInfo` (replaces biome information via `BiomeAccessor`), entry points `ForgeFluidHooks` and `ForgeTillingHooks` (`main`), mixin `FluidMixin`, and client mixin `FluidRendererMixin` (targeting Forge's `shouldDisplayFluidOverlay` call).
- Packaging and index entry points: `hostModuleResources` task, index file `META-INF/plexusmodloader/host-modules.properties`.

## Build and test
```powershell
.\gradlew.bat :plexus-fabric-api-forge-v26_2:assemble
.\gradlew.bat -PplexusMinecraft=26.2 :plexus-bootstrap:fabricApiGameTest :plexus-bootstrap:forgeFabricApiGameTest
.\gradlew.bat -PplexusMinecraft=26.2 :plexus-bootstrap:corpusFabricForge :plexus-bootstrap:corpusQuiltForge
```
- `fabricApiGameTest` (Fabric standalone) and `forgeFabricApiGameTest` (alongside Forge) check whether they produce matching lines, including test mods `fabric-biome-modification` (cactus patches added to Nether Wastes generation settings and per-step features; default Plains temperature and temperature sent to the client at 1.25) and `fabric-custom-fluid` (pigs submerged in custom fluid lose breath after 3 ticks). `forgeFabricApiGameTest` also verifies host module replacement logs (including lines for rendering-fluids) and the absence of `PLEXUS-COMPAT-225` and `Critical injection failure`.
- Fluid lines also verify swimming, sinking, and boats: drowning pigs lose breath, armor stands sink identically to water (0.14 / water 0.14), submerged boats on the floor do not rise (0.00 / water 0.00), and boats on the surface move identically to water (0.03 / water 0.03). For the boat check bridge, `BOAT_SUPPORT` in `HostPatchAdjustments`'s `FluidWrap` wraps the `checkInWater()Z` and `isUnderwater()` descriptors.
- The window task `forgeFluidClient` (`-Dplexus.forge=always`, full Fabric API jar) verifies via the client entry point of test mod `fabric-custom-fluid` whether eyes are in fluid, air is not full, and swim-sprinting functions, while also expecting rendering-fluids host module lines and "joined the game" (passes on 26.2 and 26.3). Without `plexus-bridge-forge`'s `ForgeForeignPayloads`, this client crashes with a `ClassCastException` upon entering a world.
- `corpusFabricForge`, `corpusQuiltForge`: Headless verification that runs Fabric and Quilt mods from a real mod corpus alongside Forge.
- Replacement logic is verified with the unit test `HostModulesTest` in `plexus-runtime-impl`.

## Typical flow
Identical to `plexus-fabric-api-neoforge-v26_2`. The host key is `minecraftforge-26.2-65.1.5`, and because `fabric-biome-api-v1` and `fabric-rendering-fluids-v1` are marked `always`, the host modules take their place even if the originals are loaded. `fabric-content-registries-v0` is substituted when dropped due to `224`.

## Known limitations
- Dedicated to `MinecraftForge 26.2-65.1.5`. When a new Forge release is issued, it must be rebuilt and re-verified.
- Game tests verify biome modifications and Fabric fluid drowning, swimming, boats, and in-fluid movement (`forgeFabricApiGameTest`).
- Client mixins for content-registries (`HudMixin`, `LocalPlayerMixin`) and the Forge client window were executed via `forgeFluidClient`, and a client with the full Fabric API enters a world (2026-11-01, 26.2, 26.3). `LocalPlayerMixin` was dropped with `224` because Forge calls `isInWaterOrSwimmable()` instead of `isInWater()` in `LocalPlayer.shouldStopSwimSprinting`, and was relocated to a `ForgeInjectionPoints` rule.
- In a Forge client with the full Fabric API, `fabric-data-generation-api-v1`, `fabric-object-builder-api-v1`, `fabric-renderer-api-v1`, and `fabric-screen-api-v1` are still dropped due to `224` (predating this host module). Consequently, Sodium, which nests `fabric-renderer-api-v1`, is also dropped in a client alongside Forge (`PLEXUS-COMPAT-111`; `forgeSodiumClient` fails).
