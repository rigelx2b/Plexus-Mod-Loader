# plexus-fabric-api-forge-v26_2 — MinecraftForge 26.2용 Fabric API 호스트 모듈

Plexus Mod Loader의 Mixin 브리지로도 MinecraftForge 옆에서 원래대로 실행할 수 없는 Fabric API 모듈을 위해, `MinecraftForge 26.2-65.1.5`의 훅 위에서 동작하는 자체 Fabric API 구현체("호스트 모듈", `ADR-0031`)를 빌드합니다. NeoForge용은 [`plexus-fabric-api-neoforge-v26_2`](../plexus-fabric-api-neoforge-v26_2/README.md), 26.3용은 [`plexus-fabric-api-forge-v26_3`](../plexus-fabric-api-forge-v26_3/README.md)입니다.

## 이 모듈이 하는 일
- Fabric API `0.161.0+26.2`의 같은 모듈과 공개 API가 같은 두 모듈을 빌드합니다: `fabric-biome-api-v1`(`18.0.6+plexus.forge.26.2`, 원본이 로드돼도 대체하는 `always`), `fabric-content-registries-v0`(`11.3.2+plexus.forge.26.2`).
- 소스는 Fabric API 자신의 소스(`FabricMC`, https://github.com/FabricMC/fabric)입니다. Forgified Fabric API 같은 중간 포팅을 거치지 않고, 각 파일의 헤더가 Fabric API 원본과 바꾼 점을 적습니다.
- `fabric-biome-api-v1`: 원본의 mixin은 Forge 패치에 맞지만 코드가 맞지 않아 대체합니다.
  - 원본은 `ChunkGenerator.featuresPerStep`을 `Supplier`로 써서 넣는데, Forge는 이 필드를 `ClearableLazy`로 바꿨습니다(패치된 게임 jar를 먼저 읽는 링크 검사가 원본을 `PLEXUS-COMPAT-112`로 뺍니다). 이 빌드는 필드를 쓰지 않고 Forge가 더한 `ChunkGenerator.refreshFeaturesPerStep()`을 부릅니다.
  - Forge의 `Biome`은 생성·스폰 설정을 `ModifiableBiomeInfo`에서 읽고, 코덱(`DIRECT_CODEC`, `NETWORK_CODEC`)은 그 정보의 원본에 든 기후와 효과를 씁니다. Fabric API는 `MinecraftServer` 생성자 안에서(Forge가 자기 수정자를 적용하기 전에) 생성·스폰 설정과 효과는 그 자리에서, 기후는 바이옴의 필드를 바꿔서 고칩니다. 그러면 코덱을 거치거나 클라이언트로 보내는 바이옴은 기후 변경을 잃습니다. `ForgeBiomeInfo`가 Fabric API가 고친 바이옴을 원본으로 하는 새 `ModifiableBiomeInfo`를 바이옴에 달아, Forge의 수정자가 그 위에서 시작하고 코덱이 Fabric API의 기후를 쓰게 합니다. 이것이 없으면 `forgeFabricApiGameTest`가 평원 온도로 1.25 대신 0.8을 읽습니다.
- `fabric-content-registries-v0`: Forge가 익사와 유체 점프를 `FluidType` 훅으로 옮겨(`PLEXUS-COMPAT-224`) 원본의 mixin을 걸 곳이 없습니다.
  - Fabric API의 `LivingEntityMixin`에서 `customFluidDrowning`과 `swapFluidTag`를 뺍니다(겨냥한 코드를 Forge가 바꿈).
  - 익사는 `ForgeFluidHooks`(Fabric `main` 진입점)가 Forge의 `LivingBreatheEvent`에 리스너를 달아 처리합니다. 눈이 익사하는 Fabric 유체에 잠겼을 때 숨을 못 쉬게 하되, 바닐라처럼 기포 기둥 안, 수중 호흡 가능 엔티티, 수중 호흡 효과, 무적 플레이어는 제외합니다.
  - `FluidMixin`이 Forge가 모르는 유체(예: Fabric 모드가 등록한 유체)의 `getFluidType()`을 물·용암·빈 `FluidType` 중 하나로 답하게 합니다. Forge의 기본 조회는 이런 유체에 `"Mod fluids must override getFluidType."`을 던집니다.
  - 26.2판의 `ForgeTillingHooks`(Fabric `main` 진입점)는 `TillableBlockRegistry`를 Forge의 `BlockToolModificationEvent`로 실행합니다. Forge의 `HoeItem`은 `TILLABLES`를 읽지 않고 `getToolModifiedState(HOE_TILL)`을 부르기 때문입니다.
- 라이선스는 `Apache License 2.0`이며, 각 모듈의 jar는 `LICENSE-<모듈 ID>` 파일을 담습니다.

## 무엇을 소유하는가
- 소스 세트 `src/fabricBiomeApiV1`, `src/fabricContentRegistriesV0`과 각 모듈의 `fabric.mod.json`, Mixin 설정, class tweaker.
- `fabric.mod.json`의 `custom` 필드 `plexusmodloader:host-module` (`host` `minecraftforge`, `minecraft` `26.2`, `replaces` 각 모듈 버전).
- 빌드 산출물 jar(`META-INF/plexusmodloader/host-modules/minecraftforge-26.2-65.1.5/`)와 이 모듈의 색인(`always` 표시 포함). `plexus-bridge-fabric`이 다른 호스트 모듈의 색인과 합쳐 jar에 하나로 둡니다(`hostModuleIndex` 작업).
- `plexus-bridge-fabric`에 산출물을 전달하는 소모성 구성 `hostModuleElements`.

## 무엇을 하지 않는가
- MinecraftForge가 없거나 `26.2-65.1.5`가 아닌 릴리스에서는 아무것도 하지 않습니다.
- NeoForge를 위한 것이 아닙니다.
- Forgified Fabric API를 다운로드하거나 요구하지 않습니다.
- 브리지로 이미 실행되는 Fabric API 모듈은 다시 구현하지 않습니다.
- Plexus Mod Loader의 클래스 경로에 jar를 직접 올리지 않습니다.

## 의존성
- Minecraft `26.2` (`plexus.minecraft-compile`, 모듈의 `gradle.properties`에서 `plexusCompileMinecraft=26.2`로 고정).
- Forge의 패치된 클라이언트 jar: `installForgeClient` 작업(`ForgeClientInstallTask`)이 `MinecraftForge 26.2-65.1.5` 자체 설치 프로그램(SHA-1 `0746b1dc6d93abb4b84f3f924009591b6c85eb80` 검사)을 `--installClient`로 `~/.gradle/caches/plexus-forge/26.2-65.1.5/client`에 실행(첫 사용 시 네트워크 필요)하여 생성되는 `libraries/net/minecraftforge/forge/26.2-65.1.5/forge-26.2-65.1.5-client.jar`를 기반으로 합니다. 이 jar는 공통 클래스도 담고 있어 바닐라 jar보다 앞에 둡니다.
- `ClassTweakedJarTask`(`<sourceSet>CompileGame`) 작업이 컴파일 시점에만 모듈의 클래스 트위커를 이 jar에 적용합니다.
- `compileOnly`: Forge universal jar, Forge의 이벤트 버스(`LivingBreatheEvent` 리스너용), Forge mergetool API(`Dist`, `OnlyIn`), Fabric Loader, Mixin, MixinExtras, ASM, `fabric-api-base`, `fabric-resource-loader-v1`, `fabric-lifecycle-events-v1`, jspecify, JetBrains annotations.

## 주요 진입점
- `fabric-biome-api-v1` 공개 API: `net.fabricmc.fabric.api.biome.v1`(`BiomeModifications`, `BiomeSelectors`, `NetherBiomes`, `TheEndBiomes` 등).
- `fabric-content-registries-v0` 공개 API: `net.fabricmc.fabric.api.registry`(`FlammableBlockRegistry`, `StrippableBlockRegistry`, `TillableBlockRegistry`, `OxidizableBlocksRegistry` 등), `net.fabricmc.fabric.api.registry.fluid`.
- Forge 연결: `ForgeBiomeInfo`(`BiomeAccessor`로 바이옴의 정보를 교체), 진입점 `ForgeFluidHooks`·`ForgeTillingHooks`(`main`), mixin `FluidMixin`.
- 패키징 및 색인 진입점: `hostModuleResources` 작업, 색인 파일 `META-INF/plexusmodloader/host-modules.properties`.

## 빌드·테스트
```powershell
.\gradlew.bat :plexus-fabric-api-forge-v26_2:assemble
.\gradlew.bat -PplexusMinecraft=26.2 :plexus-bootstrap:fabricApiGameTest :plexus-bootstrap:forgeFabricApiGameTest
.\gradlew.bat -PplexusMinecraft=26.2 :plexus-bootstrap:corpusFabricForge :plexus-bootstrap:corpusQuiltForge
```
- `fabricApiGameTest`(Fabric 단독)와 `forgeFabricApiGameTest`(Forge 옆)는 테스트 모드 `fabric-biome-modification`(네더 황무지의 생성 설정과 단계별 피처에 선인장 패치, 평원 기본 온도와 클라이언트로 보내는 온도 1.25)과 `fabric-custom-fluid`(자기 유체 안의 돼지가 3틱 뒤 숨을 잃음)를 포함해 같은 줄을 내는지 봅니다. `forgeFabricApiGameTest`는 호스트 모듈 대체 로그와 `PLEXUS-COMPAT-225`·`Critical injection failure` 없음도 확인합니다.
- `corpusFabricForge`, `corpusQuiltForge`: 실제 모드 묶음의 Fabric·Quilt 모드를 Forge 옆에서 실행하는 헤드리스 검증입니다.
- `plexus-runtime-impl`의 단위 테스트 `HostModulesTest`로 대체 로직을 검증합니다.

## 대표 흐름
`plexus-fabric-api-neoforge-v26_2`와 같습니다. 호스트 키가 `minecraftforge-26.2-65.1.5`이고, `fabric-biome-api-v1`은 `always` 표시라 원본이 로드돼도 호스트 모듈이 자리를 대신합니다. `fabric-content-registries-v0`은 `224`로 빠졌을 때 대체됩니다.

## 알려진 제한
- `MinecraftForge 26.2-65.1.5` 전용입니다. 새 Forge 릴리스가 나오면 다시 빌드하고 검증해야 합니다.
- 게임 테스트가 확인하는 것은 바이옴 수정과 Fabric 유체의 익사입니다. Fabric 유체의 수영, 보트, 유체 안 이동은 게임 안에서 확인하지 않았습니다.
- content-registries의 클라이언트 mixin(`HudMixin`, `LocalPlayerMixin`)을 Forge 옆에서 실행해 보지 않았습니다. Forge의 게임 창 실행도 하지 않았습니다.
- NeoForge도 Fabric 모드의 유체에 대해 같은 `getFluidType` 예외를 던집니다(바이트코드로 확인). NeoForge 호스트 모듈은 아직 이를 고치지 않았습니다.
