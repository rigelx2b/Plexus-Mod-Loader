[English](README.md) | [한국어](README.ko.md)

# plexus-bridge-fabric — Fabric & Quilt Compatibility Bridge

`plexus-bridge-fabric`는 **Fabric** 모드와 **Quilt** 모드를 Plexus Mod Loader 위에서 실제 Fabric Loader / Quilt Loader와 100% 동일한 의미론과 런타임 신뢰성으로 구동할 수 있도록 지원하는 브릿지 모듈입니다.

---

## 1. 주요 역할 및 설계 아키텍처

### 1) 완벽한 메타데이터 분석 및 버전 해석
- **Fabric 모드 (`fabric.mod.json`):**
  - 공식 Fabric Loader의 버전 명세서(`VersionPredicate`) 엔진을 내장하여 `depends`, `recommends`, `breaks`, `conflicts`, `provides` 관계를 엄격하게 해석합니다.
  - 잘못된 형식의 메타데이터는 무단 예외 대신 `PLEXUS-COMPAT-100`으로 정밀 보고합니다.
- **Quilt 모드 (`quilt.mod.json`):**
  - Quilt Loader 0.31.0 명세를 준수하여 다단계 의존성(`optional`, `unless`, `load_type` 등)을 분석합니다 (`PLEXUS-COMPAT-300..310`).
  - `fabric.mod.json`과 `quilt.mod.json`을 동시에 포함하는 모드는 Fabric 모드로 자동 판정하여 안정적으로 로드합니다.

### 2) 공개 Loader API 에뮬레이션
- `FabricLoader.getInstance()` 및 Quilt의 `QuiltLoader`, `MinecraftQuiltLoader` 공개 API를 기본 제공합니다.
- 게임 객체 조회, 공유 객체 저장소(`ObjectShare`), 설정 디렉터리(`config/`), 진입점 컨테이너를 실제 로더와 완벽히 호환되는 상태로 매핑합니다.

### 3) 바이트코드 변환 파이프라인 (`FabricClassTransformer`)
모드 클래스가 로드될 때 Fabric Loader와 동일한 정밀 순서로 변환을 적용합니다:
1. **환경 스트리핑 (`EnvironmentStripper`):**
   - `@Environment(EnvType.CLIENT/SERVER)` 어노테이션을 검사하여 반대편 사이드(Side)의 메서드/필드를 안전하게 제거합니다.
   - Quilt 모드의 경우 `@ClientOnly`, `@DedicatedServerOnly`, `@Requires` 어노테이션까지 폭넓게 처리합니다.
2. **인터페이스 주입 (`InterfaceInjector`):**
   - `accessWidener`에 정의된 `inject-interface` 구문을 파싱하여 대상 마인크래프트 클래스에 인터페이스 구현을 주입합니다.
3. **통합 Mixin 엔진 (`PlexusMixinService`):**
   - SpongePowered Mixin 0.8.7 (FabricMC 포크) 및 MixinExtras 0.5.5를 단일 서비스로 가동합니다.
   - Quilt 모드의 mixin 구성도 함께 바인딩하여 단일 패스(Single-pass)로 클래스를 변환합니다.

### 3-1) intermediary 이름 번역 (Minecraft 26.1 이전, ADR-0037)
1.20.1, 1.20.4, 1.21.1, 1.21.11용 Fabric 모드는 FabricMC의 *intermediary* 이름으로 만들어져 있고, 이 게임은 Mojang 이름으로 실행됩니다(ADR-0019). `IntermediaryTranslations.prepare(jar)`는 그런 모드 jar의 사본을 플레이어 PC에서 한 번 만들어, 게임을 Mojang 이름으로 부르게 합니다:
- **클래스 파일**(ASM `ClassRemapper`): 선언과 참조의 클래스·필드·메서드·레코드 컴포넌트 이름(게임 메서드를 재정의하는 모드 클래스, Mixin `@Shadow` 멤버)과, intermediary 이름이 든 모든 문자열(`@Inject(method=...)`, `@At(target=...)`, `@Invoker`, `@Mixin(targets=...)`, 상수).
- 모드의 Mixin 구성이 가리키는 **Mixin 참조 맵**과, `intermediary` 네임스페이스의 **class tweaker 파일**(`official`로 다시 씀).
- 입력: 다시 매핑한 게임 jar 옆의 Mojang 매핑(설치 프로그램 또는 빌드가 보관)과 FabricMC의 `intermediary-<minecraft>-v2.jar`(CC0-1.0)입니다. 후자는 없으면 같은 폴더에 한 번 받습니다. 사본은 `<reportDir>/translated-mods/<minecraft>/`에 둡니다. 어느 것도 올리거나 재배포하지 않습니다.
- 진단: `PLEXUS-COMPAT-142`(번역함), `143`(매핑 없음, 필요한 모드만 막음), `144`(실패), `145`(이름을 그대로 둠).
- 이 버전에서는 `FabricLoader.getMappingResolver()`와 `QuiltLoader.getMappingResolver()`가 `intermediary` 네임스페이스에 답합니다.
- 런타임은 `ModLoaderBridge.prepareJar(Path)`로 이 기능을 부릅니다.
- 이름 표(힙 33~48 MB)는 사본을 만들어야 할 때 읽고, 시작 준비가 끝나면(`releaseTables()`) 또는 30초 안 쓰면 놓아 줍니다. 사본이 이미 있는 시작은 매핑을 읽지 않습니다.

### 3-2) Fabric Loader 오류 창 대체 클래스
일부 모드는 Fabric Loader의 오류 창에 상태 트리를 넘깁니다(ModernFix는 다른 모드와 맞지 않는 옵션을 경고). 브리지는 `net.fabricmc.loader.impl.gui.FabricGuiEntry`를 직접 제공하고(창 없음: 트리를 로그에 쓰고, 모드가 `exitAfter`로 요청했을 때만 게임을 끕니다), `FabricStatusTree`, `FormattedException`, `Localization`은 Fabric Loader에서 그대로 담아 `PLEXUS-COMPAT-111`로 막히지 않게 합니다. `FabricBridge.providedPackages()`가 이를 알리고, `ProvidedClassesTest`가 목록의 모든 클래스가 jar에 있는지 검사합니다.

### 3-3) 모드 jar의 파일 시스템
`FabricModContainer`와 `QuiltModContainer`는 모드에 jar 안의 경로를 줍니다(`getRootPaths()`, `rootPath()`, Quilt의 `getSourcePaths()`). 모드가 경로를 계속 쥐고 있으므로 그 뒤의 zip 파일 시스템은 게임이 끝날 때까지 열어 둡니다. `SharedJarFileSystems`가 jar마다 한 번만 열어 모든 컨테이너에 같은 것을 줍니다. 이전에는 중첩 모드의 Quilt 컨테이너마다 바깥 jar를 다시 열었습니다.

### 3-4) 호스트 검사에서의 refmap 이름
Fabric 모드는 믹스인을 개발할 때 쓴 이름(`method = "tryBreakBlock"`, yarn 메서드를 가리키는 `@At`의 `target`)으로 쓰고, Mixin 설정의 `"refmap"`이 가리키는 참조 맵이 그것을 게임의 이름으로 바꿔 줍니다. 그 조회는 Mixin이 믹스인을 적용할 때 합니다. MinecraftForge·NeoForge와 함께 실행하면 `HostPatchAdjustments`가 이 문자열을 패치 전후의 게임 클래스와 비교하므로 게임의 이름이 필요합니다. yarn 이름 그대로는 메서드를 찾지 못해, 호출이나 필드를 기준으로 하는 규칙(`SAME_PLACE`, `IN_METHOD`)이 적용되지 않았고 핸들러 이름을 기준으로 하는 규칙(`HOST_COVERS`)만 작동했습니다. 이제 `HostRefmapNames`가 참조 맵을 읽고(Mixin처럼 `mappings`, 그다음 `data`의 `named:intermediary`, 믹스인 클래스와 문자열 기준) 인젝터의 `method`·`target` 문자열(중첩 `@At`·`@Slice`, MixinExtras `@Definition` 포함)을 게임의 이름으로 바꿔 믹스인 클래스에 씁니다. 믹스인을 검사할 때와 로드할 때 둘 다 같습니다. 참조 맵이 없는 모드, 맵에 없는 문자열, `remap = false`(믹스인·인젝터·`@At`)인 것은 쓰인 그대로 둡니다. 바뀐 문자열은 맵에 없으므로 Mixin은 그대로 씁니다. 26.1 이전 버전에서는 번역이 이미 Mojang 이름으로 고친 맵을 읽습니다(3-1).

### 3-5) 26.1 이전 Quilt 모드 (ADR-0048)
- **헤드리스 Quilt 모드 실행:** Quilt 모드는 Minecraft 1.20.1, 1.20.4, 1.21.1 및 1.21.11에서 헤드리스로 실행됩니다. EULA 확인 전까지의 전용 서버 실제 모드 묶음 로드/미로드(중첩 모드 포함): 1.20.1 189/821, 1.20.4 111/153, 1.21.1 109/123, 1.21.11 123/96이며, 게임 테스트 서버 실행: 1.20.4 112/153, 1.21.1 115/173, 1.21.11 123/141입니다(1.20.1 게임 테스트 서버 실행은 Cardinal Components API의 block 모듈에서 중단됩니다).
- **Quilt Config 번들 제공:** Quilt Config 1.3.3(Apache-2.0) 및 Quilt Loader의 QuiltConfig API(`QuiltConfigImpl`)가 번들로 제공되어 Quilt 모드 설정 파일을 JSON5로 저장합니다(TOML 제외).
- **Refmap 및 접근자 변환:** Intermediary→Mojang 변환(`ModJarTranslator`, `SelectorAwareClassRemapper`)은 Mixin refmap 키를 유지하고 대상 클래스 내부의 `@Accessor`/`@Invoker` 이름을 해석합니다.
- **QSL 스택 맵 제한:** 26.1 이전 Quilt Standard Libraries (QSL) / Quilted Fabric API는 다시 매핑된 게임 jar의 스택 맵 프레임이 난독화된 jar와 달라 QSL Mixin이 실패하므로 아직 실행되지 않습니다.
- **선택적 Quilted Fabric API 프로비저닝:** 1.20.1, 1.20.4, 1.21.1용 고정 Quilted Fabric API 다운로드(`QuiltedFabricApiProvision`, `PLEXUS-DISCOVERY-006` 추가 / `007` 실패)는 기본적으로 꺼져 있으며 `-Dplexus.qsl=on`으로만 활성화됩니다(1.21.11 및 26.x용 QSL은 존재하지 않습니다).

### 4) 진입점 생명주기 관리 및 언어 어댑터
- **언어 어댑터(Language Adapters):** Fabric의 `languageAdapters` 및 Quilt의 `quilt_loader.language_adapters`에 선언된 사용자 정의 언어 어댑터(`org.quiltmc.loader.api.LanguageAdapter` 또는 `net.fabricmc.loader.api.LanguageAdapter` 구현체)를 지원합니다. 양쪽 로더의 어댑터를 단일 런타임 저장소에서 통합 관리하여 상호 로더 간 진입점 생성을 지원하며, 어댑터를 찾을 수 없는 경우 Quilt 모드는 `PLEXUS-COMPAT-312`, Fabric 모드는 `PLEXUS-COMPAT-114`로 안전하게 차단합니다.
- **`pre_launch` / `preLaunch`:** Mixin 준비 직후 및 게임 초기화 직전에 호출.
- **`main` / `client` / `server`:** 마인크래프트 내부 초기화 지점에서 각 로더 인터페이스 계약에 맞추어 호출.

---

## 2. 주요 패키지 및 클래스

| 패키지 | 주요 클래스 | 역할 |
| :--- | :--- | :--- |
| `dev.plexusmodloader.compat.fabric` | `FabricBridge`, `ClassTweakerFile`, `FabricClassTransformer` | Fabric 모드 검색 및 변환 파이프라인 관리 |
| `dev.plexusmodloader.compat.fabric.mixin` | `FabricMixinBootstrap`, `PlexusMixinService`, `HostPatchAdjustments`, `HostRefmapNames`, `HostStaticCalls`, `HostTargetCalls`, `ReplacedReceiver`, `HostNeutralArguments` | 통합 Mixin 구동 및 호스트 패치 자동 적응. 호스트가 바꾼 호출에 `@WrapOperation`/`@Redirect`를 이어 주는 다리(정적 도우미, 대상 클래스의 자기 메서드 호출, 인자가 늘어난 오버로드의 중립 값) |
| `dev.plexusmodloader.compat.fabric.intermediary` | `IntermediaryTranslations`, `IntermediaryTranslation`, `ModJarTranslator`, `IntermediaryRemapper`, `ClassTweakerTranslator` | 26.1 이전 Minecraft에서 intermediary 이름 모드 jar를 Mojang 이름으로 번역(ADR-0037) |
| `dev.plexusmodloader.compat.fabric.loader` | `FabricLoaderRuntime`, `OfficialMappingResolver`, `SharedJarFileSystems` | Fabric Loader 공개 API 런타임 구현, 모드 jar마다 하나의 공유 zip 파일 시스템 |
| `net.fabricmc.loader.impl.*` | `FabricLoaderImpl`, `FabricLauncherBase`, `GameProvider`, `FabricGuiEntry` | 모드가 쓰는 Fabric Loader 내부 클래스의 대체 구현 |
| `dev.plexusmodloader.compat.quilt` | `QuiltBridge`, `QuiltModJsonReader`, `QuiltLoaderRuntime` | Quilt 메타데이터 파서 및 Quilt Loader API 구현 |

---

## 3. 검증 및 테스트 (Gradle Tasks)

```powershell
# 단위 테스트 및 Quilt API 링크 검증
.\gradlew.bat :plexus-bridge-fabric:check

# 실제 26.2 서버 환경에서 Mixin, MixinExtras, 인터페이스 주입 검증
.\gradlew.bat :plexus-bootstrap:fabricMixin

# 대규모 실전 인기 Fabric 모드 묶음(Corpus) 헤드리스 검증
.\gradlew.bat :plexus-bootstrap:corpusFabric
```

`plexus-bootstrap`의 작업들은 `test-mods/`(와 모드 묶음 목록 `plexus-bootstrap/compat/`)가 필요해서, 이 공개 저장소에서는 실행되지 않습니다.

---

## 4. 제약 사항
- 호스트 패치가 처리기의 일을 이미 하는 주입은 `HOST_COVERS` 규칙으로만 덜어냅니다(자동 아님, ADR-0032). 덜어낸 처리기의 기능은 Fabric 단독 실행과 다를 수 있습니다(MinecraftForge 옆: 위치별 파티클·벽 안 스프라이트, 자동 블록 아이템 모델).
- Quilt Standard Libraries (QSL)는 마인크래프트 26.x 대응 릴리즈가 없으므로, QSL 모듈이 필요한 모드는 시작 전 명확한 사유(`PLEXUS-COMPAT-114`와 `308`)와 함께 제외되고, QSL 진입점은 실행하지 않습니다(`303`). 예외로, 클래스가 Fabric 초기화 인터페이스를 구현한 QSL 진입점은 Fabric 진입점으로 실행하며, 그 모드의 `quilt_base` 의존은 충족된 것으로 봅니다(`PLEXUS-COMPAT-311`). 26.1 이전에는 다시 매핑된 게임 jar의 스택 맵 프레임 차이로 인해 QSL / Quilted Fabric API가 아직 실행되지 않으므로(ADR-0048), `-Dplexus.qsl=on`이 설정되지 않는 한 Quilted Fabric API를 다운로드하지 않습니다.
- 모드가 Fabric Loader나 Quilt Loader의 비공개 내부 패키지(`net.fabricmc.loader.impl.*` 등)에 직접 접근하는 경우 링크 검증 단계(`PLEXUS-COMPAT-111`)에서 거부될 수 있습니다. 모드가 실제로 쓰는 몇 가지 내부 클래스는 제공합니다(`FabricLauncher`, `FabricLoaderImpl`, `GameProvider`, `FabricGuiEntry`, `FabricStatusTree`, `FormattedException`). `MappingConfiguration`은 Fabric Loader 자신의 매핑 트리를 내주므로 제공하지 않습니다.
- Mixin 라이브러리는 이후 클래스 로드에 필요한 것(`ClassInfo` 캐시, 모든 Mixin 클래스의 클래스 노드)을 스스로 쥐고 있습니다. 브리지는 두 번째 사본을 들고 있지 않으며, 라이브러리를 고치지 않고는 그것을 놓을 수 없습니다. Fabric Loader도 같은 것을 쥡니다.
