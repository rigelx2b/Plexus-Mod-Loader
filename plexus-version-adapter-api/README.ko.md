[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-api

## 이 모듈이 하는 일
Minecraft 버전마다 다른 지식(게임 진입점, 내장 변환 규칙)을 담는 어댑터 계약을 정의합니다.

## 무엇을 소유하는가
`dev.plexusmodloader.game.version.GameVersionAdapter`, 게임 계층 계약 `dev.plexusmodloader.game.version.layer`(`GameLayer`, `GameLayerContext`, `LoadedModCounts`, `ModCountLines`, `LoadingScreenModLines`, `NotLoadedModsNotice` 등)

`ModCountLines`는 타이틀 화면에 보이는 로더별 모드 수 줄(Plexus·Fabric·Quilt·Forge·NeoForge 순서, 모드가 없는 로더는 줄 없음)과 그 번역 키(`plexus.title.mod_count.*`)를 정합니다. 번역은 `src/main/resources/assets/plexus/lang/`의 `en_us.json`, `ko_kr.json`에 있고, 언어 파일이 없거나 글자가 형식에 맞지 않으면 영어 줄을 씁니다. 로더 줄은 직접 넣은 모드 수 뒤에 `plexus.title.mod_count.bundled`로 그 안에 든 모드 수(`Fabric mods: 2 (+13 included)`)를 붙이고, 모드 폴더에서 로드되지 않은 모드가 있으면 `plexus.title.mod_count.left_out` 줄을 더합니다.

`LoadingScreenModLines`는 초기 창과 게임 계층의 로딩 화면(`PlexusLoadingOverlay`)이 막대 아래에 그리는 줄을 정합니다. 첫 줄은 막대 아래에 오는 `Mods: Fabric 2 (+13 included)  ·  NeoForge 2`이고, 모드 폴더에서 로드되지 않은 모드가 있으면 그 줄들은 막대 바로 위(로고와 막대 사이)에 호박색으로 `1 not loaded: sophisticatedbackpacks - see logs/latest.log`처럼 옵니다(`INTERNAL`, 영어만: 두 화면 모두 게임의 글꼴과 언어가 로드되기 전에 Plexus의 픽셀 글자로 그림). `LoadedModCounts`는 직접 넣은 모드 수(`installed`)와 로드되지 않은 모드(`leftOutMods`), Plexus 모드 로더가 아직 돌리지 못하는 모드(`PLEXUS-COMPAT-224`)의 ID(`hostIncompatibleModIds`)를 함께 담습니다.

`NotLoadedModsNotice`는 모드가 로드되지 않았을 때 게임 계층이 로딩 뒤 타이틀 화면이 처음 뜰 때 그 위에 띄우는 안내창의 제목과 문단을 만듭니다(`INTERNAL`). `224` 모드를 ID로 적은 문단, 모드 폴더에서 다른 이유로 빠진 모드를 적은 문단, 이유가 `logs/latest.log`에 있다는 문단입니다. 글은 번역 키 `plexus.notice.not_loaded.title`·`.host_incompatible`·`.left_out`·`.details`(`en_us`, `ko_kr`)로 찾고, 키가 없으면 영어를 씁니다. 안내창은 플레이어가 OK(또는 Esc)를 누를 때까지 남고 실행마다 한 번 뜹니다.

## 무엇을 하지 않는가
특정 버전의 클래스·메서드 이름을 담지 않습니다.

## 의존성
`plexus-runtime-api`, `plexus-transform-api`(api)

## 주요 진입점
`GameVersionAdapter`(`ServiceLoader`로 탐색)

## 빌드·테스트
```powershell
.\gradlew.bat :plexus-version-adapter-api:check
```

계약 사용은 `PlexusRuntimeTest`의 `TestGameVersionAdapter`로 검증합니다. `ModCountLinesTest`는 두 언어의 줄과 영어 대체를 검증합니다.

## 대표 흐름
런타임은 게임 jar의 `version.json` id가 `gameVersion()`과 정확히 같은 어댑터를 고릅니다. 없으면 `PLEXUS-BOOTSTRAP-002`.

## 알려진 제한
버전 범위 매칭은 없고, 정확한 버전만 매칭합니다.
