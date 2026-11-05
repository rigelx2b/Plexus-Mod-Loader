[English](README.md) | [한국어](README.ko.md)

# plexus-game-v1_20_1

## 이 모듈이 하는 일
다시 매핑된 Minecraft 1.20.1 게임 클래스를 사용하여 Plexus Mod Loader Native API를 구현합니다. 변환 게임 클래스 로더에 의해 로드됩니다(ADR-0010, ADR-0019, ADR-0035).

## 무엇을 소유하는가
`dev.plexusmodloader.game.layer.v1_20_1.GameLayer1_20_1`, `GameHooks`, `InteractionHooks`, `LifecycleHooks`의 정적 훅 메서드, 그리고 `client.ClientHooks`, `client.CreativeTabPaging`, `client.TitleScreenModCounts`, `client.ItemTooltips`, `client.PlexusParticle`, `client.PlexusBlockEntityRenderer`, `client.PlexusScreen`, `client.PlexusContainerScreen`, `client.PlexusHumanoidRenderer`의 클라이언트 훅.
또한 1.20.1용 콘텐츠, 엔티티, 명령어, 월드 생성, 게임테스트, 네트워킹 계층 어댑터를 소유합니다.

## 무엇을 하지 않는가
공개 Plexus Mod Loader API(`dev.plexusmodloader.api.*`)에 Minecraft 내부 타입을 노출하지 않습니다. 다른 Minecraft 버전을 지원하지 않습니다.

## 의존성
`plexus-version-adapter-api`, `slf4j.api`, 그리고 다시 매핑된 Minecraft 1.20.1 클라이언트 jar(`compileOnly`, `plexus.minecraft-compile` 제공).

## 주요 진입점
`GameLayer1_20_1`

## 빌드·테스트
```powershell
.\gradlew.bat :plexus-game-v1_20_1:test
```
`BuiltInRulesTest`를 실행하여 `Minecraft1_20_1Adapter`에 정의된 모든 기본 변환 규칙(서버/공통, 클라이언트)이 다시 매핑된 1.20.1 클래스에 정상 적용되고 public static 훅과 일치하는지 검증합니다.

## 제한 및 버전별 특성

- **정식 지원 버전(ADR-0035):** 1.20.1의 공통(서버) 및 클라이언트 Native API 계약을 완전히 구현합니다.
- **Configuration 네트워크 단계 부재:** Minecraft 1.20.1에는 Configuration 네트워크 단계(1.20.2에서 추가)가 없습니다. 모드의 구성 교환은 대신 로그인 쿼리로 실행합니다(`ConfigurationExchanges`). 교환마다 요청을 보내고 답이나 제한 시간을 기다리는 동안 로그인을 붙잡아 두었다가, 플레이어를 통과시키거나 연결을 거부합니다. 그 채널을 모르는 클라이언트는 데이터 없이 답하며, 이는 무응답으로 칩니다. 게임은 로그인을 600틱 뒤에 끝내므로 모드의 제한 시간을 모두 더해도 그보다 작아야 합니다.
- **렌더 파이프라인 부재:** Minecraft 1.20.1에는 렌더 파이프라인이 없습니다. 모드의 셰이더 파이프라인은 코어 셰이더 프로그램으로 만듭니다(`ModShaderPipelines`). 프로그램 파일은 `ShaderPipelineDefinition`에서 써 내므로 모드는 GLSL 1.50 코어 방언의 `.vsh`와 `.fsh`만 넣으면 되고, 모드 uniform은 `ModUniforms` 블록 대신 이름으로 선언합니다.
- **나중 버전의 팩 구조:** `LaterFormatPack`은 모드 jar의 팩을 1.20.1이 읽는 이름으로 보여 줍니다. 데이터 폴더(`tags/blocks`, `loot_tables`, `recipes`, `advancements`, `structures` 등)는 나중 버전의 단수형 폴더에서, `models/item/<이름>.json`은 아이템 정의 `items/<이름>.json`에서 응답합니다. jar나 오버레이에 옛 이름의 파일이 있으면 그 파일이 우선합니다. 바뀌는 것은 폴더뿐이고 파일 내용은 그대로이므로, 나중 형식의 레시피는 여전히 해석에 실패하니 오버레이에 따로 넣어야 합니다. 여러 모델 중 고르는 아이템 정의는 처음 적은 모델을 보여 줍니다. 모델을 하나도 적지 않은 정의는 `PLEXUS-RESOURCE-011`로 실패하고, 게임이 모델을 불러올 때 로그에 남깁니다.
- **후처리 효과 프로그램:** `EffectInstance`가 `shaders/program/<이름>.json`을 `minecraft` 네임스페이스에서 만들기 때문에, 체인에는 `"name": "<모드 ID>_<프로그램>"`을 적고 프로그램 파일은 `assets/minecraft/shaders/program/<모드 ID>_<프로그램>.json`과 `.fsh`에 둡니다. 체인 파일은 그대로 `assets/<네임스페이스>/shaders/post/<경로>.json`에 두며, `ClientView.setPostEffect`가 이 파일을 읽습니다.
- **모드 차원이 든 월드:** 게임은 차원이 기본 차원과 정확히 같을 때만 월드를 안정으로 보고(`WorldDimensions.bake`가 개수를 비교), 그 밖의 월드는 열기 전에 백업을 묻습니다. 규칙 `plexus:mod_dimension_count`(훅 `GameHooks.onBakedDimensionCount`)가 26.x처럼 기본 차원이 모두 있는지를 묻게 합니다. 모드 팩은 내장 팩으로 칩니다(`LaterFormatPack.isBuiltin`). 게임은 내장이 아닌 팩의 항목을 실험적으로 등록하고, 1.20.1에는 알려진 팩 정보가 없기 때문입니다. 모드가 아닌 데이터 팩의 차원은 여전히 실험적입니다.
- **GameTest 진입점:** 1.20.1에는 바닐라 `net.minecraft.gametest.Main`이 없으므로, 헤드리스 게임테스트는 자체 진입점 `dev.plexusmodloader.game.layer.v1_20_1.gametest.GameTestMain`에서 시작합니다.
