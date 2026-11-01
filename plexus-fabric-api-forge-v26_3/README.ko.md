[English](README.md) | [한국어](README.ko.md)

# plexus-fabric-api-forge-v26_3 — MinecraftForge 26.3용 Fabric API 호스트 모듈

Plexus Mod Loader의 Mixin 브리지로도 MinecraftForge 옆에서 원래대로 실행할 수 없는 Fabric API 모듈을 위해, `MinecraftForge 26.3-66.1.0`의 훅 위에서 동작하는 자체 Fabric API 구현체("호스트 모듈", `ADR-0031`)를 빌드합니다. 26.2판은 [`plexus-fabric-api-forge-v26_2`](../plexus-fabric-api-forge-v26_2/README.md), NeoForge용은 [`plexus-fabric-api-neoforge-v26_3`](../plexus-fabric-api-neoforge-v26_3/README.md)입니다.

## 이 모듈이 하는 일
- Fabric API `0.165.0+26.3`의 같은 모듈과 공개 API가 같은 세 모듈을 빌드합니다: `fabric-biome-api-v1`(`20.1.1+plexus.forge.26.3`, 원본이 로드돼도 대체하는 `always`), `fabric-content-registries-v0`(`15.0.6+plexus.forge.26.3`), `fabric-rendering-fluids-v1`(`6.0.6+plexus.forge.26.3`, `always`).
- 소스는 Fabric API 자신의 소스(`FabricMC`, https://github.com/FabricMC/fabric)입니다. 각 파일의 헤더가 Fabric API 원본과 바꾼 점을 적습니다. 예외로 `fabric-rendering-fluids-v1`은 Forgified Fabric API의 빌드(NeoForge용으로 이미 싣는 것과 같은 코드)에서 가져옵니다.
- 26.2판과 다른 점:
  - biome: Forge 26.3의 `Biome`은 서버 시작 때 수정된 정보를 만든 뒤 그 결과(기후, 속성, 효과, 생성 설정)를 `Biome.update`로 바이옴의 필드에 다시 씁니다. Fabric API는 그보다 먼저 `MinecraftServer` 생성자에서 생성 설정은 그 자리에서, 기후·속성(스폰 포함)·효과는 바이옴의 필드를 바꿔서 고치는데, Forge의 되쓰기가 이 변경을 되돌립니다. `ForgeBiomeInfo`가 Fabric API가 고친 바이옴을 원본으로 하는 새 `ModifiableBiomeInfo`를 달아(결과는 `BiomeAccessor`의 `update` 호출자로 돌려줌) Forge의 수정자가 그 위에서 시작하게 합니다. 26.2판은 코덱이 원본의 기후를 쓰는 것을 막는 데 같은 클래스를 씁니다. `featuresPerStep`은 26.2판처럼 `refreshFeaturesPerStep()`으로 다시 만듭니다.
  - content-registries: Fabric API 26.3은 퇴비·연료·박피·경작·평탄화·장식 항아리·양조 API를 이 모듈에서 뺐으므로 26.2판의 그 부분과 `ForgeTillingHooks`가 없습니다. 산화 단계·밀랍 블록의 도끼 벗기기는 item 모듈의 블록 변환기에 등록하므로 이 모듈은 Fabric API `0.165.0+26.3`의 `fabric-item-api-v1`에 대해서도 컴파일합니다. 유체 부분(`ForgeFluidHooks`, `FluidMixin`, `LivingEntityMixin`의 `customFluidDrowning`·`swapFluidTag` 제거)은 26.2판과 같은 접근입니다. `swapFluidTag`를 뺀 까닭으로 소스 헤더는 Forge의 `jumpInFluid`가 어느 유체인지 가리지 않는다는 점을 듭니다.
  - rendering-fluids(클라이언트): Forge(`26.3-66.1.0`, `javap`)가 `FluidRenderer.tesselate`의 바닐라 덮개 검사(`relativeBlock instanceof HalfTransparentBlock || instanceof LeavesBlock`)를 `BlockState.shouldDisplayFluidOverlay(BlockAndTintGetter, BlockPos, FluidState)`로 바꿨습니다(NeoForge의 것은 `BlockAndLightGetter`를 받음). 원본 `FluidRendererMixin`(`modifyNonOverlayCheck`, MixinExtras 표현식 `? instanceof HalfTransparentBlock`)은 Forge의 `FluidRenderer`에서 대상을 찾지 못해 첫 청크를 그릴 때 `PLEXUS-COMPAT-225`("Critical injection failure ... Scanned 0 target(s)")로 게임을 멈추고, 실행 전 검사는 표현식 주입 지점을 보지 않아 예견하지 못합니다. 이 빌드는 덮개 검사를 Forge의 `shouldDisplayFluidOverlay` 호출에 맞춥니다. `FluidRenderingRegistryImpl.isBlockTransparent(block, Forge의 답)`은 Fabric 모드가 그 블록을 등록하지 않았으면 Forge의 답을 둡니다.
- 라이선스는 `Apache License 2.0`이며, 각 모듈의 jar는 `LICENSE-<모듈 ID>` 파일을 담습니다.

## 무엇을 소유하는가
- 소스 세트 `src/fabricBiomeApiV1`, `src/fabricContentRegistriesV0`, `src/fabricRenderingFluidsV1`과 각 모듈의 `fabric.mod.json`, Mixin 설정, class tweaker(biome).
- `fabric.mod.json`의 `custom` 필드 `plexusmodloader:host-module` (`host` `minecraftforge`, `minecraft` `26.3`, `replaces` 각 모듈 버전).
- 빌드 산출물 jar(`META-INF/plexusmodloader/host-modules/minecraftforge-26.3-66.1.0/`)와 이 모듈의 색인(`always` 표시 포함). `plexus-bridge-fabric`이 다른 호스트 모듈의 색인과 합쳐 jar에 하나로 둡니다(`hostModuleIndex` 작업).
- `plexus-bridge-fabric`에 산출물을 전달하는 소모성 구성 `hostModuleElements`.

## 무엇을 하지 않는가
- MinecraftForge가 없거나 `26.3-66.1.0`이 아닌 릴리스에서는 아무것도 하지 않습니다.
- NeoForge를 위한 것이 아닙니다.
- Forgified Fabric API를 다운로드하거나 요구하지 않습니다.
- 브리지로 이미 실행되는 Fabric API 모듈은 다시 구현하지 않습니다.
- Plexus Mod Loader의 클래스 경로에 jar를 직접 올리지 않습니다.

## 의존성
- Minecraft `26.3` (`plexus.minecraft-compile`, 모듈의 `gradle.properties`에서 `plexusCompileMinecraft=26.3`로 고정).
- Forge의 패치된 클라이언트 jar: `installForgeClient` 작업(`ForgeClientInstallTask`)이 `MinecraftForge 26.3-66.1.0` 자체 설치 프로그램(SHA-1 `0325b11053e85ae08b19e73be00871e1a5ca3a55` 검사)을 `--installClient`로 `~/.gradle/caches/plexus-forge/26.3-66.1.0/client`에 실행(첫 사용 시 네트워크 필요)하여 생성되는 `libraries/net/minecraftforge/forge/26.3-66.1.0/forge-26.3-66.1.0-client.jar`를 기반으로 합니다. 이 jar는 공통 클래스도 담고 있어 바닐라 jar보다 앞에 둡니다.
- `ClassTweakedJarTask`(`<sourceSet>CompileGame`) 작업이 컴파일 시점에만 모듈의 클래스 트위커를 이 jar에 적용합니다.
- `compileOnly`: Forge `26.3-66.1.0` universal jar(버전 카탈로그의 `forge263`), Forge의 이벤트 버스, Forge mergetool API, Fabric Loader, Mixin, MixinExtras, ASM, `fabric-api-base`, `fabric-resource-loader-v1`, `fabric-lifecycle-events-v1`, Fabric API 26.3의 `fabric-item-api-v1`(`14.8.0+d1c0d72b5d`), jspecify, JetBrains annotations.

## 주요 진입점
- `fabric-biome-api-v1` 공개 API: `net.fabricmc.fabric.api.biome.v1`(`BiomeModifications`, `BiomeSelectors`, `NetherBiomes`, `TheEndBiomes` 등).
- `fabric-content-registries-v0` 공개 API: `net.fabricmc.fabric.api.registry`(`FlammableBlockRegistry`, `OxidizableBlocksRegistry`, `VillagerInteractionRegistries` 등), `net.fabricmc.fabric.api.registry.fluid`.
- `fabric-rendering-fluids-v1` 공개 API(클라이언트): `net.fabricmc.fabric.api.client.render.fluid.v1`.
- Forge 연결: `ForgeBiomeInfo`(`BiomeAccessor`로 바이옴의 정보를 교체), 진입점 `ForgeFluidHooks`(`main`), mixin `FluidMixin`, 클라이언트 mixin `FluidRendererMixin`(Forge의 `shouldDisplayFluidOverlay` 호출을 겨냥).
- 패키징 및 색인 진입점: `hostModuleResources` 작업, 색인 파일 `META-INF/plexusmodloader/host-modules.properties`.

## 빌드·테스트
```powershell
.\gradlew.bat :plexus-fabric-api-forge-v26_3:assemble
.\gradlew.bat -PplexusMinecraft=26.3 :plexus-bootstrap:fabricApiGameTest :plexus-bootstrap:forgeFabricApiGameTest
.\gradlew.bat -PplexusMinecraft=26.3 :plexus-bootstrap:corpusFabricForge :plexus-bootstrap:corpusQuiltForge
```
- 26.3의 `fabricApiGameTest`(Fabric 단독)와 `forgeFabricApiGameTest`(Forge 옆, 26.3에서는 이 작업으로 새로 추가)는 테스트 모드 `fabric-biome-modification`(네더 황무지의 생성 설정에 선인장 패치, 평원 기본 온도와 클라이언트로 보내는 온도 1.25)과 `fabric-custom-fluid`(자기 유체 안의 돼지가 3틱 뒤 숨을 잃음)를 포함해 같은 줄을 내는지 봅니다. 26.3의 게임 테스트 서버는 네더도 평평하게 만들어, 단계별 피처 줄 대신 "네더 생성기가 평평해 바이옴 피처를 두지 않는다"는 줄을 기대합니다. `forgeFabricApiGameTest`는 호스트 모듈 대체 로그(rendering-fluids의 줄 포함)와 `PLEXUS-COMPAT-225`·`Critical injection failure` 없음도 확인합니다.
- 유체 줄은 수영·가라앉기·보트까지 확인합니다: 익사하는 돼지가 숨을 잃고, 갑옷 거치대는 물과 같게 가라앉고(0.14 / 물 0.14), 바닥의 보트는 오르지 않고(0.00 / 물 0.00), 수면의 보트는 물과 같게 움직입니다(0.03 / 물 0.03). 보트 검사 다리는 `HostPatchAdjustments`의 `FluidWrap` `BOAT_SUPPORT`가 `checkInWater()Z`와 `isUnderwater()` 서술자를 덮어 둡니다.
- 창 작업 `forgeFluidClient`(`-Dplexus.forge=always`, Fabric API jar 전체)는 테스트 모드 `fabric-custom-fluid`의 클라이언트 진입점으로 눈이 유체 안인지, 공기가 가득 차 있지 않은지, 달리면서 수영하는지를 확인하고, rendering-fluids 호스트 모듈 줄과 "joined the game"도 기대합니다(26.2, 26.3 통과). `plexus-bridge-forge`의 `ForgeForeignPayloads`가 없으면 이 클라이언트는 월드에 들어갈 때 `ClassCastException`으로 멈춥니다.
- `corpusFabricForge`, `corpusQuiltForge`: 실제 모드 묶음의 Fabric·Quilt 모드를 Forge 옆에서 실행하는 헤드리스 검증입니다.

## 대표 흐름
`plexus-fabric-api-neoforge-v26_2`와 같습니다. 호스트 키가 `minecraftforge-26.3-66.1.0`이고, `fabric-biome-api-v1`과 `fabric-rendering-fluids-v1`은 `always` 표시라 원본이 로드돼도 호스트 모듈이 자리를 대신합니다. `fabric-content-registries-v0`은 `224`로 빠졌을 때 대체됩니다.

## 알려진 제한
- `MinecraftForge 26.3-66.1.0` 전용입니다. 새 Forge 릴리스가 나오면 다시 빌드하고 검증해야 합니다.
- 게임 테스트는 바이옴 수정과 Fabric 유체의 익사·수영·보트·유체 안 이동을 확인합니다(`forgeFabricApiGameTest`).
- 26.3의 게임 테스트 서버 네더는 평평해서 바이옴 피처가 실제로 놓이는지는 이 환경에서 확인할 수 없습니다.
- content-registries의 클라이언트 mixin(`HudMixin`, `LocalPlayerMixin`)과 Forge 클라이언트 창은 `forgeFluidClient`로 실행했고, Fabric API 전체를 넣은 클라이언트가 월드에 들어갑니다(2026-11-01, 26.2, 26.3). `LocalPlayerMixin`은 Forge가 `LocalPlayer.shouldStopSwimSprinting`에서 `isInWater()` 대신 `isInWaterOrSwimmable()`을 부르는 탓에 `224`였으며 `ForgeInjectionPoints` 규칙으로 옮겼습니다.
- 26.3 클라이언트의 `fabric-item-api-v1`은 `MultiPlayerGameModeMixin`이 `224`였습니다(Forge가 `MultiPlayerGameMode.sameDestroyTarget`의 `ItemStack.isSameItemSameComponents(mainHand, destroyingItem)`를 `!destroyingItem.shouldCauseBlockBreakReset(mainHand)`로 바꿈). `ForgeInjectionPoints` 규칙과 생성된 `@WrapOperation` 다리(`ItemWrap` 종류 `BLOCK_BREAK_RESET`)가 Forge의 답이 끊을 때만, Fabric 처리기(`allowContinuingBlockBreaking`)가 이어가게 하지 않을 때만 부수기를 끊게 합니다. 손에 든 아이템이 바뀔 때 블록 부수기가 이어지는지는 게임 안에서 확인하지 않았습니다.
- Fabric API 전체를 넣은 Forge 클라이언트에서 `fabric-data-generation-api-v1`, `fabric-object-builder-api-v1`, `fabric-renderer-api-v1`, `fabric-screen-api-v1`은 아직 `224`로 빠집니다(이번 호스트 모듈 이전부터). 그 때문에 `fabric-renderer-api-v1`을 중첩한 Sodium도 Forge 옆 클라이언트에서 빠집니다(`PLEXUS-COMPAT-111`; `forgeSodiumClient` 실패).
