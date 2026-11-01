[English](README.md) | [한국어](README.ko.md)

# plexus-fabric-api-forge-v26_3 — Fabric API Host Module for MinecraftForge 26.3

Builds custom Fabric API implementations ("host modules", `ADR-0031`) operating on hooks in `MinecraftForge 26.3-66.1.0` for Fabric API modules that cannot run unmodified alongside MinecraftForge even with Plexus Mod Loader's Mixin bridge. The 26.2 edition is [`plexus-fabric-api-forge-v26_2`](../plexus-fabric-api-forge-v26_2/README.md), and the edition for NeoForge is [`plexus-fabric-api-neoforge-v26_3`](../plexus-fabric-api-neoforge-v26_3/README.md).

## What This Module Does
- Builds three modules with the same public API as the corresponding modules in Fabric API `0.165.0+26.3`: `fabric-biome-api-v1` (`20.1.1+plexus.forge.26.3`, marked `always` to replace the original even if loaded), `fabric-content-registries-v0` (`15.0.6+plexus.forge.26.3`), and `fabric-rendering-fluids-v1` (`6.0.6+plexus.forge.26.3`, `always`).
- Source code comes from Fabric API itself (`FabricMC`, https://github.com/FabricMC/fabric). Each file header notes the changes made relative to upstream Fabric API. As an exception, `fabric-rendering-fluids-v1` is sourced from a build of Forgified Fabric API (the same code already included for NeoForge).
- Differences from the 26.2 edition:
  - biome: Forge 26.3's `Biome` creates modified information during server start and then writes the results (climate, attributes, effects, generation settings) back into the biome's fields via `Biome.update`. Fabric API modifies biomes earlier in the `MinecraftServer` constructor—generation settings in-place, and climate, attributes (including spawns), and effects by mutating the biome's fields—but Forge's overwrite reverts these changes. `ForgeBiomeInfo` attaches a new `ModifiableBiomeInfo` with the Fabric API-modified biome as the original (returning the result to the caller of `update` in `BiomeAccessor`) so Forge's modifiers start on top of it. The 26.2 edition uses the same class to prevent codecs from using the original climate. `featuresPerStep` is rebuilt using `refreshFeaturesPerStep()`, as in the 26.2 edition.
  - content-registries: Because Fabric API 26.3 removed the composting, fuel, stripping, tilling, flattening, decorated pot, and brewing APIs from this module, those parts from the 26.2 edition and `ForgeTillingHooks` are absent. Because axe-scraping of oxidation stages and waxed blocks is registered to the block transformations in the item module, this module also compiles against `fabric-item-api-v1` from Fabric API `0.165.0+26.3`. The fluid portion (`ForgeFluidHooks`, `FluidMixin`, and the removal of `customFluidDrowning` and `swapFluidTag` in `LivingEntityMixin`) follows the same approach as the 26.2 edition. As the reason for omitting `swapFluidTag`, the source header cites that Forge's `jumpInFluid` does not discriminate which fluid it is.
  - rendering-fluids (client): Forge (`26.3-66.1.0`, `javap`) replaced the vanilla overlay check in `FluidRenderer.tesselate` (`relativeBlock instanceof HalfTransparentBlock || instanceof LeavesBlock`) with `BlockState.shouldDisplayFluidOverlay(BlockAndTintGetter, BlockPos, FluidState)` (NeoForge's takes `BlockAndLightGetter`). Upstream `FluidRendererMixin` (`modifyNonOverlayCheck`, MixinExtras expression `? instanceof HalfTransparentBlock`) fails to find the target in Forge's `FluidRenderer`, crashing the game with `PLEXUS-COMPAT-225` ("Critical injection failure ... Scanned 0 target(s)") when rendering the first chunk, which pre-launch verification cannot anticipate because it does not inspect expression injection points. This build aligns the overlay check with Forge's `shouldDisplayFluidOverlay` call. `FluidRenderingRegistryImpl.isBlockTransparent(block, forgeResponse)` keeps Forge's response if a Fabric mod has not registered that block.
- Licensed under `Apache License 2.0`, and each module's jar contains a `LICENSE-<module ID>` file.

## What It Owns
- Source sets `src/fabricBiomeApiV1`, `src/fabricContentRegistriesV0`, and `src/fabricRenderingFluidsV1`, along with each module's `fabric.mod.json`, Mixin configs, and class tweaker (biome).
- The `plexusmodloader:host-module` `custom` field in `fabric.mod.json` (`host` `minecraftforge`, `minecraft` `26.3`, `replaces` each module version).
- Build artifact jars (`META-INF/plexusmodloader/host-modules/minecraftforge-26.3-66.1.0/`) and this module's index (including `always` markers). `plexus-bridge-fabric` merges this with indices from other host modules into a single index in the jar (`hostModuleIndex` task).
- The consumable configuration `hostModuleElements`, which passes artifacts to `plexus-bridge-fabric`.

## What It Does Not Do
- Does nothing when MinecraftForge is absent or is not the `26.3-66.1.0` release.
- Not intended for NeoForge.
- Does not download or require Forgified Fabric API.
- Does not reimplement Fabric API modules that already run via the bridge.
- Does not place jars directly onto Plexus Mod Loader's classpath.

## Dependencies
- Minecraft `26.3` (`plexus.minecraft-compile`, pinned to `plexusCompileMinecraft=26.3` in the module's `gradle.properties`).
- Forge's patched client jar: Based on `libraries/net/minecraftforge/forge/26.3-66.1.0/forge-26.3-66.1.0-client.jar`, generated by the `installForgeClient` task (`ForgeClientInstallTask`) running the official `MinecraftForge 26.3-66.1.0` installer (verified with SHA-1 `0325b11053e85ae08b19e73be00871e1a5ca3a55`) with `--installClient` into `~/.gradle/caches/plexus-forge/26.3-66.1.0/client` (network required on first run). This jar also contains common classes, so it is placed before the vanilla jar.
- The `ClassTweakedJarTask` (`<sourceSet>CompileGame`) task applies the module's class tweaker to this jar at compile time only.
- `compileOnly`: Forge `26.3-66.1.0` universal jar (`forge263` in the version catalog), Forge's event bus, Forge mergetool API, Fabric Loader, Mixin, MixinExtras, ASM, `fabric-api-base`, `fabric-resource-loader-v1`, `fabric-lifecycle-events-v1`, `fabric-item-api-v1` (`14.8.0+d1c0d72b5d`) from Fabric API 26.3, jspecify, JetBrains annotations.

## Key Entry Points
- `fabric-biome-api-v1` public API: `net.fabricmc.fabric.api.biome.v1` (`BiomeModifications`, `BiomeSelectors`, `NetherBiomes`, `TheEndBiomes`, etc.).
- `fabric-content-registries-v0` public API: `net.fabricmc.fabric.api.registry` (`FlammableBlockRegistry`, `OxidizableBlocksRegistry`, `VillagerInteractionRegistries`, etc.), `net.fabricmc.fabric.api.registry.fluid`.
- `fabric-rendering-fluids-v1` public API (client): `net.fabricmc.fabric.api.client.render.fluid.v1`.
- Forge integration: `ForgeBiomeInfo` (replaces biome info via `BiomeAccessor`), entry point `ForgeFluidHooks` (`main`), mixin `FluidMixin`, client mixin `FluidRendererMixin` (targeting Forge's `shouldDisplayFluidOverlay` call).
- Packaging and indexing entry points: `hostModuleResources` task, index file `META-INF/plexusmodloader/host-modules.properties`.

## Build and Test
```powershell
.\gradlew.bat :plexus-fabric-api-forge-v26_3:assemble
.\gradlew.bat -PplexusMinecraft=26.3 :plexus-bootstrap:fabricApiGameTest :plexus-bootstrap:forgeFabricApiGameTest
.\gradlew.bat -PplexusMinecraft=26.3 :plexus-bootstrap:corpusFabricForge :plexus-bootstrap:corpusQuiltForge
```
- On 26.3, `fabricApiGameTest` (Fabric standalone) and `forgeFabricApiGameTest` (alongside Forge, newly added as this task in 26.3) check whether both produce identical lines, including test mods `fabric-biome-modification` (cactus patch in Nether Wastes generation settings, plains default temperature and client-synced temperature 1.25) and `fabric-custom-fluid` (pig in its custom fluid loses breath after 3 ticks). The 26.3 game test server makes the Nether flat as well, expecting the line "the nether generator is flat; it places no biome features" instead of the step-by-step feature line. `forgeFabricApiGameTest` also verifies host module replacement logs (including the rendering-fluids line) and the absence of `PLEXUS-COMPAT-225` and `Critical injection failure`.
- Fluid lines check swimming, sinking, and boats as well: the drowning pig loses breath, armor stands sink identically to water (0.14 / water 0.14), boats on the bottom do not rise (0.00 / water 0.00), and boats on the surface move identically to water (0.03 / water 0.03). For the boat check bridge, `BOAT_SUPPORT` in `FluidWrap` of `HostPatchAdjustments` wraps the `checkInWater()Z` and `isUnderwater()` descriptors.
- The window task `forgeFluidClient` (`-Dplexus.forge=always`, full Fabric API jar) uses the client entry point of test mod `fabric-custom-fluid` to check whether eyes are in fluid, air is not full, and sprint-swimming functions, while also expecting the rendering-fluids host module line and "joined the game" (passes on 26.2 and 26.3). Without `ForgeForeignPayloads` from `plexus-bridge-forge`, this client crashes with a `ClassCastException` upon joining the world.
- `corpusFabricForge`, `corpusQuiltForge`: Headless verification running Fabric and Quilt mods from a real-world mod corpus alongside Forge.

## Representative Flow
Identical to `plexus-fabric-api-neoforge-v26_2`. The host key is `minecraftforge-26.3-66.1.0`, and `fabric-biome-api-v1` and `fabric-rendering-fluids-v1` are marked `always`, so the host module takes their place even if the original is loaded. `fabric-content-registries-v0` is substituted when dropped with `224`.

## Known Limitations
- Exclusive to `MinecraftForge 26.3-66.1.0`. When a new Forge release is issued, it must be rebuilt and verified.
- Game tests verify biome modifications and Fabric fluid drowning, swimming, boats, and in-fluid movement (`forgeFabricApiGameTest`).
- Because the Nether in the 26.3 game test server is flat, whether biome features are actually placed cannot be verified in this environment.
- Client mixins of content-registries (`HudMixin`, `LocalPlayerMixin`) and the Forge client window were run via `forgeFluidClient`, and a client with the full Fabric API joins the world (2026-11-01, 26.2, 26.3). `LocalPlayerMixin` previously failed with `224` because Forge calls `isInWaterOrSwimmable()` instead of `isInWater()` in `LocalPlayer.shouldStopSwimSprinting`, and was moved to a `ForgeInjectionPoints` rule.
- In the 26.3 client, `fabric-item-api-v1` experienced `224` in `MultiPlayerGameModeMixin` (Forge replaced `ItemStack.isSameItemSameComponents(mainHand, destroyingItem)` in `MultiPlayerGameMode.sameDestroyTarget` with `!destroyingItem.shouldCauseBlockBreakReset(mainHand)`). A `ForgeInjectionPoints` rule and a generated `@WrapOperation` bridge (`ItemWrap` type `BLOCK_BREAK_RESET`) ensure that block breaking is reset only when Forge's answer resets it and the Fabric handler (`allowContinuingBlockBreaking`) does not allow it to continue. Whether continuing block breaking works when changing held items has not been verified in-game.
- In a Forge client with the full Fabric API, `fabric-data-generation-api-v1`, `fabric-object-builder-api-v1`, `fabric-renderer-api-v1`, and `fabric-screen-api-v1` still drop with `224` (predating this host module). Consequently, Sodium, which nests `fabric-renderer-api-v1`, also drops on the client alongside Forge (`PLEXUS-COMPAT-111`; `forgeSodiumClient` fails).
