[English](README.md) | [한국어](README.ko.md)

# plexus-game-v1_21_1

## 이 모듈이 하는 일
다시 매핑된 Minecraft 1.21.1 게임 클래스를 사용하여 Plexus Mod Loader Native API를 구현합니다. 변환 게임 클래스 로더에 의해 로드됩니다(ADR-0010).

## 무엇을 소유하는가
`dev.plexusmodloader.game.layer.v1_21_1.GameLayer1_21_1`, `GameHooks`, `InteractionHooks`, `LifecycleHooks`의 정적 훅 메서드, 그리고 `client.*`의 클라이언트 전용 계층:

- `ClientHooks`: 키 바인딩, `ClientEvents.TICK`·`KEY_INPUT`·`MOUSE_BUTTON`·`MOUSE_SCROLL`·`FIELD_OF_VIEW`·`JOINED_WORLD`·`LEFT_WORLD`, HUD 레이어, 모드 메뉴 화면, 클라이언트·구성 단계 페이로드, `sendToServer`, 파티클 제공자, 블록 엔티티·엔티티 렌더러, 모드 에셋 리로드 리스너, 월드 페인터, `ClientView`(화면, 카메라, 후처리 효과, 월드 나가기).
- `ItemTooltips`(`ClientEvents.ITEM_TOOLTIP`), `InputCodes`, `DynamicTextures`, `ModShaderPipelines`, `WorldPainting`(셰이더 쿼드, 선, 상자, 점, 글자).
- 화면과 렌더러: `PlexusScreen`, `PlexusContainerScreen`, `PlexusBlockEntityRenderer`, `PlexusModelRenderer`, `PlexusHumanoidRenderer`, `PlexusEntityModel`, `PlexusParticle`.
- `CreativeTabPaging`, `TitleScreenModCounts`, `NotLoadedModsScreen`.

## 무엇을 하지 않는가
공개 Plexus Mod Loader API(`dev.plexusmodloader.api.*`)에 Minecraft 내부 타입을 노출하지 않습니다. 다른 Minecraft 버전을 지원하지 않습니다.

## 의존성
`plexus-version-adapter-api`, `slf4j.api`, 그리고 다시 매핑된 Minecraft 1.21.1 클라이언트 jar(`compileOnly`, `plexus.minecraft-compile` 제공).

## 주요 진입점
`GameLayer1_21_1`

## 빌드·테스트
```powershell
.\gradlew.bat :plexus-game-v1_21_1:check
```
`BuiltInRulesTest`를 실행하여 `Minecraft1_21_1Adapter`의 모든 변환 규칙(클라이언트 규칙은 클라이언트로, 나머지는 전용 서버로)을 다시 매핑된 1.21.1 클래스에 계획·적용하고, 끼워 넣은 호출마다 서술자가 정확히 같은 public static 훅에 닿는지 검증합니다. 클라이언트 테스트는 입력 코드 변환, ARGB→ABGR 픽셀 변환, 툴팁 줄 뷰, std140 유니폼 배치, 생성한 셰이더 JSON, 후처리 효과 파일 이름을 다룹니다.

## 클라이언트 계층의 흐름
`Minecraft1_21_1Adapter`가 게임의 메서드(`Minecraft.tick`, `KeyboardHandler.keyPress`, `MouseHandler.onPress`·`onScroll`, `Gui.render`, `LevelRenderer.renderLevel`, `GameRenderer.getFov`, `ClientPacketListener.handleCustomPayload`, `ParticleEngine.registerProviders`, `MenuScreens.create` 등)에 `ClientHooks` 호출을 끼워 넣습니다. 훅은 런타임이 `LayerState`에 모아 둔 선언을 읽고 컨텍스트의 이벤트 버스로 이벤트를 발행합니다. 모드의 HUD 레이어·월드 페인터·블록 엔티티 페인터가 예외를 던지면 `PLEXUS-CLIENT-003`으로 한 번 기록하고 그 항목만 끕니다.

## 제한

1.21.1이 1.21.11과 같은 방식으로 할 수 없는 곳입니다. 모두 코드가 있는 로그로 실패하거나 아래처럼 동작하며, 조용히 건너뛰는 것은 없습니다.

- **셰이더는 1.21.1 셰이더 방언을 씁니다.** 모드 셰이더 소스는 `assets/<네임스페이스>/shaders/core/<이름>.vsh`·`.fsh`(`#version 150`)여야 하고, 파이프라인의 셰이더 위치는 `core/`로 시작해야 합니다. 1.21.1의 `ShaderInstance`가 코어 셰이더를 그 폴더에서만 읽기 때문이며, 다른 위치는 `PLEXUS-CLIENT-006`으로 실패하고 쿼드는 건너뜁니다. 1.21.11의 `#moj_import <minecraft:dynamictransforms.glsl>`은 없으므로 `uniform mat4 ModelViewMat;`, `ProjMat`, `vec4 ColorModulator`, `float GameTime`, 안개 유니폼, `vec2 ScreenSize`를 일반 유니폼으로 선언하세요. 소스가 언급하는 것은 Plexus Mod Loader가 셰이더 JSON에 선언해 줍니다. `ModUniforms`는 그대로 `layout(std140) uniform ModUniforms { ... };` 블록이며, Plexus Mod Loader가 OpenGL로 유니폼 버퍼를 만들고, 블록이 없거나 크기가 선언한 필드의 std140 크기와 다르면 `PLEXUS-CLIENT-006`으로 그 파이프라인을 거부합니다. 여러 버전을 대상으로 하는 모드는 셰이더 에셋을 버전별로 따로 둬야 합니다.
- 셰이더 파이프라인은 처음 쓸 때 만들고 리소스 리로드(F3+T)로 다시 만들지 않습니다. 셰이더 파일을 고친 뒤에는 클라이언트를 다시 시작하세요.
- **후처리 효과**는 1.21.11의 `post_effect/<경로>.json`이 아니라 1.21.1의 체인 형식을 `assets/<네임스페이스>/shaders/post/<경로>.json`에서 읽습니다. 파일이 없으면 `setPostEffect`가 `false`를 돌려줍니다. `EffectInstance`가 `shaders/program/<이름>.json`을 `minecraft` 네임스페이스에서 만들기 때문에, 체인에는 `"name": "<모드 ID>_<프로그램>"`을 적고 프로그램 파일은 `assets/minecraft/shaders/program/<모드 ID>_<프로그램>.json`과 `.fsh`에 둡니다.
- **아이템 모델**은 `models/item/<이름>.json`에서 읽습니다. 1.21.1에는 아이템 정의(`items/<이름>.json`, 1.21.4에서 추가)가 없기 때문입니다. `LaterFormatPack`이 이 이름에 정의로 응답하고, 같은 이름의 모델 파일이 있으면 그 파일이 우선합니다. 여러 모델 중 고르는 정의는 처음 적은 모델을 보여 줍니다. 모델을 하나도 적지 않은 정의는 `PLEXUS-RESOURCE-011`로 실패하고, 게임이 모델을 불러올 때 로그에 남깁니다.
- **모드 차원이 든 월드**는 백업 질문 없이 열립니다. 게임은 차원이 기본 차원과 정확히 같을 때만 월드를 안정으로 보는데(`WorldDimensions.bake`가 개수를 비교), 규칙 `plexus:mod_dimension_count`(훅 `GameHooks.onBakedDimensionCount`)가 26.x처럼 기본 차원이 모두 있는지를 묻게 합니다. 모드가 아닌 데이터 팩의 차원은 여전히 실험적입니다.
- **월드 페인터의 점**은 1.21.1에 기즈모 모음이 없어 화면 기준 점이 아니라 작은 정육면체(반변 `size * 0.0125`블록)로 그립니다. 선은 요청한 굵기로 게임의 선 셰이더를 쓰고, 상자는 외곽선과 반투명 채움이며, 글자는 빌보드입니다(`onTop` 글자는 벽 너머로 보임).
- **키 바인딩 범주**는 1.21.1에서 번역 키입니다. 모드의 조작 범주는 `key.category.<모드 ID>.keybinds`로, 1.21.11이 만드는 키와 같아서 언어 파일 하나로 두 버전을 모두 씁니다.
- **`DynamicTexture` 픽셀**은 올릴 때 `0xAARRGGBB`를 게임의 `0xAABBGGRR`로 바꿉니다.
- 게임 창에서 실행하지 않은 것: 키·마우스·휠 이벤트, HUD 레이어, 모드 화면과 메뉴, 블록 엔티티·몹 렌더러, 파티클, 셰이더 파이프라인, 월드 페인터, 동적 텍스처, 후처리 효과, 아이템 툴팁, 키 바인딩, 카메라 제어는 실제 1.21.1 클래스에 대한 컴파일과 모든 규칙을 적용하는 `BuiltInRulesTest`로 확인했으며, 그려진 화면을 본 것은 아닙니다. `runClient`, `clientWorldReloadTest`, `launcherProfileTest`는 창을 열기 때문에 1.21.1에서 실행하지 않았습니다.
