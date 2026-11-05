[English](README.md) | [한국어](README.ko.md)

# plexus-game-v1_20_4

## 이 모듈이 하는 일
Mojang 이름으로 다시 매핑한 Minecraft 1.20.4 게임 클래스 위에서 Plexus Mod Loader Native API를 구현합니다(ADR-0019, ADR-0035). 공통(서버) 쪽은 게임에서 동작하고 검증되었습니다. 모드 콘텐츠, 명령어, 네트워크, 월드 서비스, 생성 데이터, 태그, 속성, 엔티티, 월드 생성, 월드 백업, 모드 게임 테스트가 해당됩니다. 클라이언트 쪽은 구현되어 컴파일되고 규칙이 실제 1.20.4 클래스에 적용되지만, 창이 필요한 것은 아직 실행하지 않았습니다(제한 참고). 변환 게임 클래스 로더가 로드합니다(ADR-0010).

## 무엇을 소유하는가
- `GameLayer1_20_4`(진입점), `LayerState`
- `GameHooks`(공통 훅), `InteractionHooks`(상호작용 이벤트와 취소할 수 있는 블록 이벤트), `LifecycleHooks`(서버·월드·리스폰·엔티티 생명주기 이벤트)
- `client`의 클라이언트 쪽: `ClientHooks`(키 바인딩, 입력·틱 이벤트, FOV, 접속·퇴장, HUD, 클라이언트·설정 단계 페이로드, 메뉴 화면, 파티클·블록 엔티티·엔티티 렌더러, 에셋 리로드, 월드 페인터, 후처리 효과, `ClientView`), `InputCodes`, `ItemTooltips`, `CreativeTabPaging`, `TitleScreenModCounts`, `NotLoadedModsScreen`, `DynamicTextures`, `PlexusScreen`, `PlexusContainerScreen`, `PlexusParticle`, `PlexusBlockEntityRenderer`, `PlexusEntityModel`, `PlexusModelRenderer`, `PlexusHumanoidRenderer`, `ModShaderPipelines`, `WorldPainting`
- `content`(`PlexusBlock`, `PlexusItem`, `ContentInstaller`, `ModCreativeTabs`, `ItemComponents`, `PlexusMenu`), `entity`(`EntityInstaller`, `PlexusMob`, `PlexusProjectile`, `PlexusMobEffect`), `commands`, `worldgen`(`CodeWorldgen`, `PlexusChunkGenerator`), `gametest`(`GameTestMain`, `GameTests`, `GameTestContextView`)
- `Views`(게임 객체를 API 뷰로), `WorldServices`(월드 저장소와 예약 작업), `Networking`, `SyncChannel`, `ConfigurationExchanges`, `EntityData`, `GameTags`, `GeneratedDataPack`, `ModPacks`, `ModReloadListener`, `WorldBackups`, `WorldgenModifier`, `PermissionChecks`

## 무엇을 하지 않는가
- 변환 규칙을 선언하지 않습니다. 규칙은 `plexus-version-adapter-v1_20_4`의 `Minecraft1_20_4Adapter`에 있습니다.
- 선언을 검증하지 않습니다. 검증은 `plexus-runtime-impl`이 합니다.
- 공개 API(`dev.plexusmodloader.api.*`)에 Minecraft 타입을 노출하지 않고, 다른 Minecraft 버전을 지원하지 않습니다.

## 의존성
`plexus-version-adapter-api`, `slf4j.api`, 그리고 다시 매핑된 Minecraft 1.20.4 클라이언트 jar(`compileOnly`, `plexus.minecraft-compile` 제공).

## 주요 진입점
`GameLayer1_20_4`. 1.20.4 서버 jar에는 `net.minecraft.gametest.Main`이 없으므로 게임 테스트 서버는 `gametest.GameTestMain`에서 시작합니다.

## 빌드·테스트
```powershell
.\gradlew.bat :plexus-game-v1_20_4:check "-PplexusMinecraft=1.20.4"
.\gradlew.bat :plexus-bootstrap:gameTests "-PplexusTests=*:*" "-PplexusMinecraft=1.20.4"
```
`BuiltInRulesTest`는 `Minecraft1_20_4Adapter`의 모든 규칙(클라이언트 규칙 포함)을 다시 매핑한 1.20.4 클래스에 적용하고, 끼워 넣은 호출마다 훅이 있는지 확인합니다. `NetworkingTest`(선언한 채널의 페이로드 바이트), `WorldStoragePersistenceTest`(게임의 `DimensionDataStorage`에 월드 저장소를 쓰고 읽음), `ItemComponentsTest`(실제 `ItemStack`의 컴포넌트를 저장하고 불러옴), `GeneratedDataPackTest`, `OperationsTest`, `ClientLogicTest`(입력 값, 셰이더 프로그램 파일, 경로, 툴팁), `PlexusEntityModelTest`(몹 모델과 키프레임 애니메이션 굽기)가 창 없이 계층을 검사합니다. `gameTests`는 모드의 게임 내 테스트를 Mojang 게임 테스트 서버에서 실행합니다.

## 1.21.1 계층과 다른 점
- **아이템 컴포넌트**는 1.20.4에 데이터 컴포넌트가 없어서 스택 NBT의 `plexus_components` 태그입니다. 스택과 함께 이동하고 저장됩니다.
- **페이로드**는 채널 ID와 바이트이며, 1.21.1의 스트림 코덱 대신 두 사용자 지정 페이로드 패킷의 비공개 `readPayload`에서 해석합니다(훅 `GameHooks.onReadPayload`). 플레이와 설정 단계 리스너는 `handleCustomPayload` 하나를 공유합니다.
- **속성 수정자**는 UUID로 구분합니다. 수정자 ID는 이름 기반 UUID로 바뀝니다.
- **블록** 콜백은 1.20.4의 public `use`, `getShape`, `randomTick` 등입니다. 모드의 `onUse`는 게임이 "아이템 없이 클릭 사용"을 블록에 맡길 때와 같게 주손에서만 실행됩니다.
- **블록 엔티티**는 `saveAdditional(CompoundTag)`와 `load(CompoundTag)`로 저장합니다. 인벤토리가 있는 모드 블록 엔티티는 `RandomizableContainerBlockEntity`를 상속합니다.
- **데이터 팩 폴더**는 복수형입니다: `tags/blocks`, `tags/items`, `tags/entity_types`, `structures`.
- **게임 규칙**은 1.20.4에 범위가 없어서, 선언한 최솟값·최댓값을 값이 바뀔 때 적용합니다.
- 플레이어가 아닌 **엔티티의 차원 이동**은 `Entity.teleportTo(ServerLevel, ...)`처럼 대상 월드에 새로 만들어 옮깁니다.

## 클라이언트 쪽이 1.21.x와 다른 점
- **렌더링이 즉시 방식입니다.** 1.20.4는 블록 엔티티와 엔티티를 `MultiBufferSource`로 그리며 렌더 상태나 `SubmitNodeCollector`가 없습니다. 블록 엔티티 페인터는 게임이 블록 엔티티를 그리는 동안 그리고, 몹 모델은 `HierarchicalModel<PlexusMob>`을 상속해 몹의 `AnimationState`를 읽으며, 월드 페인터는 `LevelRenderer.renderLevel` 끝에서 그립니다.
- **월드 페인터 도형**은 1.20.4에 기즈모가 없어서 여기서 만듭니다. 선은 두께별 렌더 타입(깊이 테스트 유무 포함), 박스는 선 박스와 채운 박스, 글자는 이름표처럼 카메라를 보는 빌보드(배율 1이 이름표의 0.025), 점은 작은 정육면체(크기 1픽셀당 1/32 블록)입니다.
- **셰이더 파이프라인**은 게임의 코어 셰이더를 씁니다. 1.20.4에는 렌더 파이프라인도 모드용 유니폼 블록도 없어서, `ModShaderPipelines`가 `ShaderPipelineDefinition`으로 게임이 기대하는 프로그램 파일(`shaders/core/<이름>.json`)을 만들어 줍니다. 그래서 모드는 `.vsh`와 `.fsh`만 GLSL 1.50 core 방언으로 냅니다: `uniform mat4 ModelViewMat`, `ProjMat`, `uniform vec4 ColorModulator`, `uniform float GameTime`, `uniform sampler2D Sampler0..`, 각 `ShaderUniform`은 `ModUniforms` 블록이 아니라 자기 이름의 일반 `uniform float/vec2/vec3/vec4`입니다. 26.x·1.21.11용 셰이더 소스는 여기서 컴파일되지 않으므로 모드는 1.20.4용을 리소스 팩 오버레이로 냅니다.
- **후처리 효과**는 `assets/<네임스페이스>/shaders/post/<경로>.json` 체인과 `shaders/program/`의 프로그램입니다(이후 버전은 `post_effect/<경로>.json`). `ClientView.setPostEffect`는 같은 ID를 받아 1.20.4 파일을 읽습니다.
- **키 바인딩**의 카테고리는 번역 키 `key.categories.<모드 ID>`입니다.
- **메뉴·파티클**: 모드 메뉴 화면은 `MenuScreens.create`의 규칙으로 열고, 파티클 공급자는 비공개 `ParticleEngine.register`에 패키지 비공개 팩토리 인터페이스의 프록시를 넘겨 등록합니다.

## 제한

- **창이 필요한 것은 하나도 실행하지 않았습니다.** 클라이언트 규칙은 실제 1.20.4 클래스에 적용되어 `BuiltInRulesTest`를 통과하고 창이 필요 없는 논리는 테스트했지만(`ClientLogicTest`, `PlexusEntityModelTest`), 클라이언트를 띄운 적은 없습니다. HUD, 화면, 키 바인딩, 툴팁, 파티클, 렌더러, 셰이더, 후처리 효과, 월드 페인터, 클라이언트로의 페이로드 전달은 **아직 검증하지 않았습니다**.
- **셰이더 방언.** 26.x·1.21.11용으로 쓴 셰이더 소스(유니폼 블록 `#moj_import`·`#include`, `ModUniforms` 블록, `layout(location = ...)`)는 1.20.4에서 컴파일되지 않고 `PLEXUS-CLIENT-006`으로 보고됩니다. 방언은 위 "셰이더 파이프라인"을 보세요. GL 컨텍스트 없이는 셰이더를 컴파일할 수 없으므로, 모드의 1.20.4 셰이더는 창을 열기 전까지 확인되지 않은 상태입니다.
- **월드 페인터의 근사.** 선 두께는 선 렌더 타입의 `lineWidth`(게임의 선 셰이더) 값이고, 점은 정육면체이며, 글자 크기는 이름표 크기에 배율을 곱한 값입니다. 26.x 기즈모와 세부가 다릅니다.
- **모드 유니폼**은 렌더 타입을 설정할 때 값을 읽으므로 그 배치의 모든 사각형이 같은 값을 씁니다. `ShaderUniform` 이름은 유효한 GLSL 식별자여야 하고(API가 이미 요구함), 프로그램이 직접 선언하는 `ModelViewMat`, `ProjMat`, `ColorModulator`, `GameTime`일 수 없습니다(`PLEXUS-CLIENT-006`).
- **파이프라인당 텍스처**는 `ShaderPipelineDefinition.MAX_TEXTURES`의 4개까지이며 `Sampler0..3`으로 묶습니다.
- **네트워크는 바이트 수준에서 검증했고**(`NetworkingTest`) 접속하는 클라이언트로는 검증하지 않았습니다. 설정 단계 교환(`ConfigurationExchanges`, `ClientHooks.onConfigurationPayload`)은 클라이언트가 접속해야 실행되며 이를 다루는 게임 테스트는 없습니다.
- **나중 버전의 팩 구조.** `LaterFormatPack`은 모드 jar의 팩을 1.20.4가 읽는 이름으로 보여 줍니다. 데이터 폴더(`tags/blocks`, `loot_tables`, `recipes`, `advancements`, `structures` 등)는 나중 버전의 단수형 폴더에서, `models/item/<이름>.json`은 아이템 정의 `items/<이름>.json`에서 응답합니다. jar나 오버레이에 옛 이름의 파일이 있으면 그 파일이 우선합니다. 여러 모델 중 고르는 아이템 정의는 처음 적은 모델을 보여 줍니다. 모델을 하나도 적지 않은 정의는 `PLEXUS-RESOURCE-011`로 실패하고, 게임이 모델을 불러올 때 로그에 남깁니다.
- 바뀌는 것은 폴더뿐이고 파일 내용은 그대로입니다. 나중 파일 형식의 데이터와 셰이더는 1.20.4용 사본이 따로 필요합니다. 모드는 항목에 `formats`(1.20.4의 키)도 적은 `pack.mcmeta` 오버레이로 이를 실을 수 있습니다. 데이터 팩은 형식 26, 리소스 팩은 형식 22입니다.
- **후처리 효과 프로그램**은 `minecraft` 네임스페이스에서 읽습니다. `EffectInstance`가 `shaders/program/<이름>.json`을 그곳에서 만들기 때문입니다. 체인에는 `"name": "<모드 ID>_<프로그램>"`을 적고 프로그램 파일은 `assets/minecraft/shaders/program/<모드 ID>_<프로그램>.json`과 `.fsh`에 둡니다.
- **모드 차원이 든 월드.** 게임은 차원이 기본 차원과 정확히 같을 때만 월드를 안정으로 보고(`WorldDimensions.bake`가 개수를 비교), 그 밖의 월드는 열기 전에 백업을 묻습니다. 규칙 `plexus:mod_dimension_count`(훅 `GameHooks.onBakedDimensionCount`)가 26.x처럼 기본 차원이 모두 있는지를 묻게 합니다. 모드 팩은 내장 팩으로 칩니다(`LaterFormatPack.isBuiltin`). 게임은 내장이 아닌 팩의 항목을 실험적으로 등록하고, 1.20.4에는 알려진 팩 정보가 없기 때문입니다. 모드가 아닌 데이터 팩의 차원은 여전히 실험적입니다.
- 1.20.4에 대응이 없어 옮기지 않은 것: 26.x의 초기 창 제목과 로딩 오버레이(`PlexusWindowTitle`, `PlexusLoadingOverlay`). 26.3 전용입니다.
