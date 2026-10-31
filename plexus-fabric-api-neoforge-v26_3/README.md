# plexus-fabric-api-neoforge-v26_3 — NeoForge 26.3용 Fabric API 호스트 모듈

Plexus Mod Loader의 Mixin 브리지로도 NeoForge 옆에서 원래대로 실행할 수 없는 Fabric API 모듈을 위해, `NeoForge 26.3.0.98`의 훅 위에서 동작하는 자체 Fabric API 구현체("호스트 모듈", `ADR-0031`)를 빌드합니다. 26.2판은 [`plexus-fabric-api-neoforge-v26_2`](../plexus-fabric-api-neoforge-v26_2/README.md)입니다.

## 이 모듈이 하는 일
- Fabric API `0.165.0+26.3`의 같은 모듈과 공개 API가 같은 아홉 모듈을 빌드합니다: `fabric-block-api-v1`(`3.1.0+plexus.neoforge.26.3`), `fabric-item-api-v1`(`14.8.0+plexus.neoforge.26.3`), `fabric-registry-sync-v0`(`8.0.4+plexus.neoforge.26.3`), `fabric-object-builder-api-v1`(`24.1.12+plexus.neoforge.26.3`, 원본이 로드돼도 대체하는 `always`), `fabric-content-registries-v0`(`15.0.6+plexus.neoforge.26.3`), `fabric-resource-conditions-api-v1`(`6.2.0+plexus.neoforge.26.3`), `fabric-data-generation-api-v1`(`27.4.2+plexus.neoforge.26.3`), `fabric-menu-api-v1`(`2.0.30+plexus.neoforge.26.3`), `fabric-entity-events-v1`(`6.0.4+plexus.neoforge.26.3`).
- Sinytra Forgified Fabric API에는 26.3 브랜치가 없습니다. 그래서 소스는 26.2 호스트 모듈(FFAPI `26.2.x` 커밋 `40f8ce0` + Plexus Mod Loader 변경)에 Fabric API의 `0.161.0+26.2`→`0.165.0+26.3` 변경을 3방향 병합으로 더한 것입니다(같은 palantir 형식으로 맞춘 뒤 병합). 각 파일의 헤더가 출처와 변경을 적습니다. 26.3에 새로 생긴 파일은 Fabric API `0.165.0+26.3`에서 가져왔습니다.
- 26.2판과 다른 점:
  - registry-sync: NeoForge 26.3은 `DataPackRegistryEvent.NewRegistry` 대신 `NewDatapackRegistryEvent`(world·reloadable 레지스트리를 따로 받음)를 씁니다. `DynamicRegistriesImpl`이 26.3 공개 API(`getAllDynamicRegistries`, `getWorldRegistries`, `getReloadableRegistries`, `registerReloadable`)를 이 이벤트와 게임의 레지스트리 목록(NeoForge 패치가 모든 로더의 레지스트리로 채움) 위에서 구현합니다. 새 `NewDatapackRegistryEventAccessor`가 이벤트를 만들고 처리합니다. `FabricRegistryInit`의 `onInitialize`는 내장 레지스트리에 속성(`SYNCED`, 새 `SAVE_DATA_VALIDATED`)만 줍니다. 동기화는 NeoForge가 합니다. 26.3의 저장 데이터 검사(`MinecraftServerMixin`, 클라이언트 `WorldOpenFlowsMixin` 등)는 그대로 둡니다. 게임의 레지스트리 목록을 Fabric 사본으로 바꾸는 `ReloadableServerRegistriesMixin`·`RegistriesDatapackGeneratorMixin`은 NeoForge 옆에서는 게임 목록이 이미 모든 레지스트리를 담으므로 넣지 않습니다.
  - item: 툴팁 순서 API가 `TooltipProvider.Getter`를 받습니다. NeoForge 26.3의 `TooltipAppender.createComponentAppender(type, getter)`로 등록합니다. 새 블록 변환 API(`BlockTransformerEvents`, `BlockTransformerHelper`)와 `FishingHookMixin`은 원본 그대로입니다(NeoForge 26.3 패치에 맞음). `FirstPersonHandsAndItemsMixin`(26.2의 `ItemInHandRendererMixin`)은 FFAPI처럼 넣지 않습니다. `ResourceUtil`은 NeoForge 모드의 데이터 팩도 모드 출처로 봅니다(FFAPI의 조건).
  - content-registries: Fabric API 26.3은 퇴비·연료·박피·경작·평탄화·장식 항아리·양조·주민 음식 API를 이 모듈에서 뺐으므로 26.2판의 그 부분(FFAPI의 퇴비·연료 데이터맵 연결, `TillableBlockRegistry` 리스너)도 빠집니다. 26.3에서 클래스 트위커가 없어지고 `EntityMixin`이 `EntityFluidExtension`을 직접 구현합니다. 산화 단계·밀랍 블록은 item 모듈의 블록 변환기(`BlockTransformerHelperImpl`)로 도끼 벗기기도 등록하므로 item 소스 세트에 대해 컴파일합니다. NeoForge 26.3에 맞춘 것: `EntityFluidInteraction`이 흐름을 `FluidType`별 `CurrentAccumulator`(필드 `currentAccumulators`)에 모으므로 `ContentRegistriesImpl`과 새 `EntityFluidInteractionCurrentAccumulatorAccessor`가 그것을 읽고, `EntityFluidInteraction.update`가 `boolean`을 돌려주므로 `EntityMixin`의 감싸기를 맞춥니다. NeoForge 26.3의 `DataMapHooks`는 산화 단계와 밀랍 블록을 데이터맵에서만 찾습니다(26.2는 게임의 맵으로 돌아감). 그래서 `DataMapModifications`가 Fabric 모드가 등록한 쌍을 `OXIDIZABLES`·`WAXABLES` 데이터맵 값으로 돌려주고, 새 `DataMapHooksMixin`이 NeoForge가 역방향 맵(번개, `WeatheringCopper.getFirst`)을 다시 만든 뒤 그 쌍을 더합니다.
  - resource-conditions: 26.2에는 호스트 모듈이 없습니다(원본이 다리로 실행됨). 26.3판은 Fabric API `0.165.0+26.3`의 모듈 그대로이고 `SimpleJsonResourceReloadListenerMixin`만 FFAPI의 것입니다. Fabric API 26.3은 JSON 데이터의 조건을 바닐라 26.3의 `prepare` 안 `Codec.parse`에서 검사하고(처리기가 `this.lister`를 읽는 인스턴스 메서드) 저장 람다 `lambda$prepare$0`에서 건너뛰는데, NeoForge 26.3의 `prepare`는 그 대신 static `scanDirectory`(맵을 돌려줌)를 부르고 거기서 조건부 코덱으로 파싱합니다. 그래서 FFAPI처럼 `scanDirectory`의 파싱을 감싸고 조건이 맞지 않으면 NeoForge의 건너뛰기 값 `DataResult.success(Optional.empty())`를 돌려줍니다. 26.3은 레시피를 레지스트리로 읽으므로 FFAPI의 `scanDirectoryWithModifier` 대상은 뺍니다. 레지스트리 경로(`RegistryLoadTaskPendingRegistrationMixin`)는 원본처럼 `NeoForgeInjectionPoints` 규칙으로 NeoForge의 조건부 코덱 호출 앞에서 실행됩니다.
  - data-generation: 26.2 호스트 모듈에 Fabric API 26.3 변경을 병합했습니다. 26.3의 새 공개 인터페이스 `FabricTagBuilder`가 NeoForge `ITagBuilderExtension`을 상속하며 제거 목록은 NeoForge `TagBuilder`의 것입니다(새 `TagBuilderMixin`은 인터페이스만 더함). `withConditions`는 NeoForge의 조건 인자를 받는 `RecipeOutput.accept`를 구현합니다. Fabric API 26.3이 더한 `BlockLootSubProviderMixin.onlyVanillaBlocks`·`EntityLootSubProviderMixin.onlyVanillaEntities`(바닐라 전리품 생성기가 `minecraft` 네임스페이스만 보게 함)는 NeoForge 26.3 패치에서 그 호출이 없어 빼고 FFAPI의 빈 mixin을 둡니다. NeoForge 26.3의 `run`은 `getKnownBlocks`·`getKnownEntityTypes`를 돌고, `VanillaBlockLoot`·`VanillaEntityLoot`가 이미 `minecraft` 네임스페이스만 돌려줍니다.
  - menu: 26.2판과 같습니다(NeoForge 26.3의 `openMenu`·`IContainerFactory`·`IMenuProviderExtension`이 26.2와 같음). Fabric API 26.3에서 바뀐 것은 `ExtendedMenuType`의 JavaDoc 예제뿐이라 그 파일만 26.3 파일에 FFAPI의 코드 변경을 다시 적용했습니다.
  - entity-events: 26.2에는 호스트 모듈이 없습니다(원본이 다리로 실행됨). 26.3판은 Fabric API `0.165.0+26.3`의 모듈 그대로이고, `ServerPlayerMixin`의 `redirectSleepDirection`·`hasNoMonstersNearby`만 NeoForge 26.3이 `startSleepInBed`의 검사를 옮긴 `lambda$startSleepInBed$0(BlockState, BedRule, BlockPos, boolean)`을 대상으로 삼고 그 매개변수를 받습니다(Fabric 26.3의 처리기는 `startSleepInBed`의 `(AbstractBedBlock, BlockState, BedRule, BlockPos)`를 받아 다리가 옮길 수 없었음). 엘리트라 mixin이 ASM 상수를 쓰므로 `asm`에 대해 컴파일합니다.
  - object-builder: Fabric API 26.3은 `PoiTypes.register` 접근 확대를 `fabric-transitive-access-wideners-v1`로 옮겼습니다. 이 빌드는 그 모듈에 대해 컴파일하지 않으므로 자기 class tweaker에 그 줄을 남깁니다.
- 라이선스는 `Apache License 2.0`이며, jar는 `LICENSE_fabric-api`를 담습니다.

## 무엇을 소유하는가
- 소스 세트 `src/fabricBlockApiV1`, `src/fabricItemApiV1`, `src/fabricRegistrySyncV0`, `src/fabricObjectBuilderApiV1`, `src/fabricContentRegistriesV0`, `src/fabricResourceConditionsApiV1`, `src/fabricDataGenerationApiV1`, `src/fabricMenuApiV1`, `src/fabricEntityEventsV1`과 각 모듈의 `fabric.mod.json`, Mixin 설정, class tweaker.
- `fabric.mod.json`의 `custom` 필드 `plexusmodloader:host-module` (`host` `neoforge`, `minecraft` `26.3`, `replaces` 각 모듈 버전).
- 빌드 산출물 jar(`META-INF/plexusmodloader/host-modules/neoforge-26.3.0.98/`)와 이 모듈의 색인. `plexus-bridge-fabric`이 26.2판의 색인과 합쳐 jar에 하나로 둡니다(`hostModuleIndex` 작업).
- `plexus-bridge-fabric`에 산출물을 전달하는 소모성 구성 `hostModuleElements`.

## 무엇을 하지 않는가
- NeoForge가 없거나 NeoForge 26.3.0.98이 아닌 릴리스에서는 아무것도 하지 않습니다.
- MinecraftForge를 위한 것이 아닙니다.
- Forgified Fabric API를 다운로드하거나 요구하지 않습니다.

## 의존성
- Minecraft `26.3` (`plexusCompileMinecraft=26.3`).
- `installNeoForgeClient` 작업이 NeoForge `26.3.0.98` 자체 설치 프로그램(SHA-1 `c3d8feb159c1abeb89af6e10e1be7b6e080d0eb9` 검사)으로 만든 패치된 클라이언트 jar(`~/.gradle/caches/plexus-neoforge/26.3.0.98/client`, 첫 사용 시 네트워크 필요).
- `compileOnly`: NeoForge `26.3.0.98` universal jar, FML 12 loader, NeoForge 이벤트 버스, mergetool API, Fabric Loader, Mixin, MixinExtras, `fabric-api-base`, `fabric-resource-loader-v1`, `fabric-lifecycle-events-v1`, jspecify, JetBrains annotations. object-builder는 registry-sync 소스 세트에, content-registries는 item 소스 세트에, data-generation은 registry-sync·resource-conditions 소스 세트에 대해 컴파일합니다. data-generation은 Fabric API의 tag·convention-tags·recipe·resource-conditions 모듈 jar(26.2판)에도 대해 컴파일합니다.

## 주요 진입점
- 각 모듈의 공개 API(`net.fabricmc.fabric.api.block.v1`, `net.fabricmc.fabric.api.item.v1`, `net.fabricmc.fabric.api.event.registry`, `net.fabricmc.fabric.api.object.builder.v1`, `net.fabricmc.fabric.api.registry`).
- NeoForge 연결: `RegistryManagerMixin`, `NewDatapackRegistryEventAccessor`, `GameDataMixin`, `CommonHooksAccessor`, `ItemTooltipHandlerMixin`, `IItemExtensionMixin`, `IBlockExtensionMixin`, `IBlockStateExtensionMixin`, `BaseRegistryMixin`, `DataMapHooksMixin`.
- 진입점: `FabricRegistryInit`(`main`), `ContentRegistriesImpl`(`main`), `ResourceConditionsImpl`(`main`), `ClientItemEventHooks`(`client`).

## 빌드·테스트
```powershell
.\gradlew.bat :plexus-fabric-api-neoforge-v26_3:assemble
.\gradlew.bat -PplexusMinecraft=26.3 :plexus-bootstrap:fabricApiGameTest :plexus-bootstrap:neoForgeFabricApiGameTest
.\gradlew.bat -PplexusMinecraft=26.3 :plexus-bootstrap:corpusFabricNeoForge
```
- 26.3의 `fabricApiGameTest`(Fabric 단독)와 `neoForgeFabricApiGameTest`(NeoForge 옆)는 테스트 모드 `fabric-block-item`, `fabric-dynamic-registry`, `fabric-object-builder`, `fabric-oxidizable-blocks`, `fabric-resource-conditions`, `fabric-data-generation`, `fabric-menu`, `fabric-entity-events`가 같은 30줄을 내는지 봅니다. 26.3에서는 `fabric-api-events`를 쓰지 않습니다(26.2용으로 쓰였음).

## 대표 흐름
`plexus-fabric-api-neoforge-v26_2`와 같습니다. 호스트 키가 `neoforge-26.3.0.98`입니다.

## 알려진 제한
- `NeoForge 26.3.0.98` 전용입니다. 새 NeoForge 릴리스가 나오면 다시 빌드하고 검증해야 합니다.
- content-registries는 26.2판처럼 Fabric 유체 동작의 수영·익사·유체 점프·유체 안 이동·보트를 NeoForge `FluidType`에 맡깁니다. 게임 테스트는 유체 동작을 확인하지 않습니다.
- 클라이언트의 `fabric-lifecycle-events-v1`은 2026-11-01부터 `NeoForgeInjectionPoints`의 `handleUpdateTags` 규칙으로 로드됩니다(태그 로드 이벤트가 NeoForge가 검색 트리를 다시 만드는 `CreativeModeTabs.allTabs()` 앞에서 실행). 창에서는 확인하지 않았습니다.
- data-generation은 태그 제공자만 게임 테스트로 확인합니다. 실제 데이터 생성 실행(`-Dfabric-api.datagen`)과 레시피·모델·전리품 생성기는 확인하지 않았습니다.
- menu는 26.2판처럼 서버에서 메뉴가 열리고 열기 데이터를 요청하는 데까지만 확인합니다. 클라이언트 화면은 확인하지 않았습니다.
- FFAPI의 26.3판이 없어, 26.3에서 바뀐 부분은 Plexus Mod Loader가 직접 맞췄습니다. 창(클라이언트)에서 툴팁·저장 데이터 검사 화면은 확인하지 않았습니다.
