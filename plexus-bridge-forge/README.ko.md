[English](README.md) | [한국어](README.ko.md)

# plexus-bridge-forge — MinecraftForge & NeoForge Compatibility Bridge

`plexus-bridge-forge`는 **MinecraftForge**와 **NeoForge** 모드를 Plexus Mod Loader의 단일 JVM 런타임 위에서 가상화하고, Fabric 및 Quilt 모드와 공존할 수 있도록 바이트코드와 FML 서비스 계층을 조율하는 핵심 호환성 브릿지 모듈입니다.

---

## 1. 주요 역할 및 설계 아키텍처

### 1) FML(Forge Mod Loader) 런타임 가상화
Forge와 NeoForge는 고유한 클래스로더 구조(ModLauncher 및 FML 모듈 레이어)를 전제하고 설계되었습니다. Plexus는 이를 다음과 같은 방식으로 가상화합니다:
- **`cpw.mods.modlauncher` 계약 구현:**
  - ModLauncher(LGPL) 코드를 복제하지 않고, 공개 인터페이스 계약을 자체 구현한 경량 가상화 서비스(`src/forgeHost`, `forge-host.jar`)를 제공합니다.
  - FML의 BOOT, SERVICE, PLUGIN, GAME 레이어를 Plexus의 단일 게임 클래스로더(`GameClassLoader`)가 모듈로 정의합니다.
- **NeoForge FML 호스팅 (26.1·26.2는 FML 11, 26.3은 FML 12):**
  - `neoforge-host.jar`(`src/neoForgeHost`)의 `PlexusFml`을 통해 `FMLLoader`의 게임 레이어 생성과 모드 검색을 인게임 바이트코드 변환 체인에 직접 통합합니다.

### 2) Fabric / Quilt 모드와의 단일 게임 공존
- **통합 Mixin 엔진 (`FmlSharedMixin`):**
  - Forge/NeoForge가 별도의 Mixin 인스턴스를 시작하지 않도록 가로채고, FabricMC의 SpongePowered Mixin 0.8.7 엔진 하나가 모든 로더의 Mixin 구성을 통합 처리합니다.
- **레지스트리 동적 제어 (`NeoForgeRegistryOpening`):**
  - NeoForge는 부팅 초기에 레지스트리를 동결(`freeze`)합니다. Plexus는 Fabric 모드 초기화 진입점이 실행되는 동안 루트 레지스트리를 일시적으로 안전하게 개방하여 `Registry is already frozen` 예외를 방지합니다.
- **호스트 패치 동적 적응 (`HostPatchAdjustments` & `NeoForgeInjectionPoints`):**
  - Forge나 NeoForge가 바닐라 클래스를 바이너리 패치하여 기존 Mixin의 인젝션 지점(Method Call, Field Access 등)이 이동한 경우, 런타임에 바이트코드 주입 대상을 올바른 대체 메서드로 리타겟합니다.
  - `fabric-content-registries-v0` 등 일부 Fabric API 모듈의 주입점은 이렇게 옮겨 그대로 동작합니다. 옮길 수 없는 믹스인이 남은 모듈은 `PLEXUS-COMPAT-224`로 제외됩니다(26.2 마지막 측정: Forge 옆 10개, NeoForge 옆 16개).
  - `ForgeInjectionPoints`에는 호출을 옮기는 규칙 외에 `HOST_COVERS`(호스트의 자기 코드가 처리기 하나의 일을 이미 하면 그 처리기만 덜어냄)와 `HOST_NEUTRAL_ARGUMENT`(호스트가 인자를 더한 호출의 `@Redirect`를 중립 값일 때만 실행) 규칙이 있습니다(ADR-0032). `HOST_COVERS`는 처리기마다 읽고 손으로 쓴 규칙에만 쓰며, Forge 옆에서 위치별 파티클·벽 안 스프라이트를 고르는 Fabric 렌더러 모델은 기본 스프라이트를 보이고 Fabric 데이터 생성은 블록 아이템 모델을 자동으로 만들지 않습니다.

### 3) 상호 모드 인식 (Cross-Loader Mod Visibility)
- **`ForeignModList` / `ForeignModIndex`:**
  - Fabric 모드가 `FabricLoader.getInstance().isModLoaded(...)`로 Forge 모드를 조회할 수 있고,
  - 반대로 Forge/NeoForge 모드가 `ModList.get().isLoaded(...)`로 Fabric/Quilt 모드를 조회할 때 올바른 `IModInfo` 메타데이터를 응답합니다.

### 4) 초기 Minecraft 버전 (1.20.x, 1.21.x)
- **지원 빌드:** MinecraftForge 1.20.1-47.4.28, 1.20.4-49.2.13, 1.21.1-52.1.21, 1.21.11-61.2.5와 NeoForge 20.4.253(1.20.4), 21.1.265(1.21.1), 21.11.48(1.21.11)입니다(`forge-releases.properties`, `neoforge-releases.properties`에 줄이 있으면 그 버전이 켜집니다). 1.20.1용 NeoForge는 존재하지 않습니다.
- **다시 이름 붙인 게임 jar:** Forge와 NeoForge의 바이너리 패치는 설치 도구가 이름을 바꾼 jar에 적용됩니다. 26.1 이전 버전에서는 `ForgeInstallerTools` / `NeoForgeInstallerTools`가 플레이어 PC에서 그 도구(로더 프로필이 적은 jar)를 실행하고, 그 출력에 패치를 적용합니다.
- **SRG 멤버 이름(Forge 1.20.1, 1.20.4):** 이 Forge 빌드는 Minecraft의 필드·메서드를 SRG 이름(`m_123_`, `f_123_`)으로 부릅니다. `SrgMojangNames`가 설치 도구가 쓴 매핑 파일 두 개로 SRG→Mojang 표를 만들고, `SrgTranslator`가 패치된 게임, Forge 자체 jar, 각 Forge 모드의 SRG 멤버를 FML이 읽기 전에 바꿉니다(클래스 파일, 문자열 상수, 애너테이션, 접근 변환기, Mixin refmap, 코어모드 스크립트, `META-INF/` 아래 어디에든 있는 중첩 jar). 결과는 캐시에 두며, Plexus 모드 로더의 게임 계층과 다른 브리지는 Mojang 이름만 봅니다.
- **Forge 47 실행 API(1.20.1):** `src/forgeHost47`에 FML 47용 실행 핸들러가 있습니다. FML 47의 API가 이후 빌드와 다릅니다.
- **NeoForge FML 2 및 FML 4 호스트(1.20.4, 1.21.1):** `src/neoForgeFml2Host` 및 `src/neoForgeFml4Host`가 Forge 호스트 `PlexusModLauncher`를 통해 NeoForge FML 2와 FML 4를 실행합니다. NeoForge 20.4 또는 21.1 옆에 Plexus 모드가 있어도 더 이상 실행이 중단되지 않습니다(해당 환경의 NeoForge 모드는 `ModList`를 통해 Plexus 모드를 보지 못합니다).
- **호스트 final 메서드:** `ForgeFinalMethods`는 Fabric 또는 Quilt 클래스가 오버라이드할 수 있도록 Plexus 모드 로더가 개방하는 검토된 호스트 final 메서드(Forge 47.4.28 및 49.2.13의 `Ingredient.toNetwork(FriendlyByteBuf)`)를 나열하며, 정보 `PLEXUS-COMPAT-228`로 보고합니다(ADR-0047). 목록에 없는 호스트 final 메서드는 게임을 중단하지 않고 `PLEXUS-COMPAT-224`로 모드를 제외하며, Fabric API가 필드를 확장할 때 Forge의 `field_to_method` 코어모드가 더 이상 실패하지 않습니다.
- **언어 로더 검사:** `ForgeModRequirements`는 실행 전 언어 로더 요구사항을 검사하여, 설치되지 않은 언어 로더를 요구하는 Forge 모드(예: Kotlin for Forge를 요구하는 `libIPN`)를 모든 Forge 모드를 중단하는 대신 `PLEXUS-COMPAT-209`로 제외합니다.
- **`mods.toml` 및 `neoforge.mods.toml` 동시 포함 jar:** `mods.toml`과 `neoforge.mods.toml`을 모두 포함하고 `mods.toml`이 `neoforge`에 의존하는 jar(1.20.4용 Entity Culling 등)는 NeoForge 전용 모드로 간주합니다.

---

## 2. 주요 구성 요소

| 패키지 / 클래스 | 역할 |
| :--- | :--- |
| `dev.plexusmodloader.compat.forge.NeoForgeBridge` | NeoForge 환경 감지, 설치 파일 다운로드 및 생명주기 관리 |
| `dev.plexusmodloader.compat.forge.NeoForgeGameHost` | NeoForge FML 모듈 레이어 정의 및 변환 타겟 관리 |
| `dev.plexusmodloader.compat.forge.NeoForgeInjectionPoints` | NeoForge 패치로 변경된 바닐라 메서드 주입점 대체 규칙 모음 |
| `dev.plexusmodloader.compat.forge.MinecraftForgeBridge` | MinecraftForge 바이너리 패치 세트 적용 및 호스트 셋업 |
| `dev.plexusmodloader.compat.forge.ForgeInjectionPoints` | MinecraftForge 패치 대응 주입점 대체 규칙 모음 |
| `dev.plexusmodloader.compat.forge.ForgeForeignPayloads` | MinecraftForge 쪽 `ForeignPayloads`(`ADR-0026`). Fabric이나 Quilt 모드가 게임을 함께 쓸 때 `ForgeGameHost`가 설치함. Fabric API 네트워킹의 코덱이 플러그인 채널 id `minecraft:register`·`minecraft:unregister`에서 앞서므로, Forge의 채널 목록(`ForgePayload`)이 그 코덱으로 쓰여 `ClassCastException`이 나던 문제를 막음. Forge 채널이 있는 id(`NetworkRegistry.findTarget`)의 `ForgePayload`는 Forge 자신의 코덱(`ForgeHooks.getCustomPayloadCodec`)으로 씀(플러그인 채널의 전송 형식은 같음). Forge에는 연결별 채널 검사가 없어 변환 규칙이 필요 없음. 게임 계층의 `HostPayloadHooks.encode`(규칙 `plexus:custom_payload_encoding`)가 이를 부름 |
| `dev.plexusmodloader.compat.forge.ForeignModIndex` | 타 로더의 모드 메타데이터를 FML SPI 형식으로 변환 |
| `dev.plexusmodloader.compat.forge.EarlyScreenArt` | 클라이언트에서 Forge·NeoForge 초기 로딩 창의 애니메이션(모루·여우)과 로고·아이콘을 Plexus 큐브(`earlyscreen/*.png`)로 바꿈. 원본 이미지 크기가 확인한 값과 같을 때만 모듈 리더의 `find`·`open`이 `<게임 폴더>/.plexus/early-screen`의 사본을 돌려주며, Plexus 모드 로더 자체 초기 창이 뜨지 않을 때만(`-Dplexus.earlyWindow=off`이거나 창을 열지 못했을 때) 적용됨 |
| `dev.plexusmodloader.compat.forge.ForgeEarlyWindowOff` | Plexus 모드 로더 자체 초기 창이 뜰 때 MinecraftForge의 `fmlearlydisplay` 모듈에서 `ImmediateWindowProvider` 서비스를 제거하여 FML이 자체 초기 창을 띄우지 않고 비활성화 경로(`DummyProvider`, `fml.toml`의 `earlyWindowControl = false`와 동일)를 타도록 함 |
| `dev.plexusmodloader.compat.forge.ForgeConfigsBeforeFirstFrame` | MinecraftForge 클라이언트에서 규칙 `plexus:forge_configs_before_first_frame`으로 `Minecraft` 생성자의 `ImmediateWindowHandler.loadingOverlay` 호출을 가로채, Forge가 모드의 클라이언트·공통 설정을 읽는 `CONFIG_LOAD`를 끝낼 때까지(로딩 실패 또는 20초까지) 첫 프레임을 늦춥니다. 매 프레임 설정을 읽는 모드(Shoulder Surfing Reloaded)가 새 설치에서 멈추던 문제를 막습니다. |
| `dev.plexusmodloader.compat.forge.EarlyWindowTitle` | 초기 로딩 창 제목을 `Minecraft* Plexus <버전>`으로 바꿈(`DisplayWindow`의 제목 상수가 정확히 한 번일 때만, 상수 풀 문자열만 고쳐 코드·스택 맵은 그대로) |

---

## 3. 검증 및 테스트 (Gradle Tasks)

```powershell
# 단위 테스트(`ForgeForeignPayloadsTest` 포함) 및 주입점 대체 규칙 검증
.\gradlew.bat :plexus-bridge-forge:check

# Forge 26.2 + Plexus 네이티브 모드 통합 게임 테스트
.\gradlew.bat :plexus-bootstrap:forgeGameTest

# NeoForge 26.2 + 네이티브 모드 헤드리스 게임 테스트
.\gradlew.bat :plexus-bootstrap:neoForgeGameTest

# Forge + Fabric 복합 인기 모드 묶음(Corpus) 헤드리스 검증
.\gradlew.bat :plexus-bootstrap:corpusFabricForge
```

`plexus-bootstrap`의 작업들은 `test-mods/`(와 모드 묶음 목록 `plexus-bootstrap/compat/`)가 필요해서, 이 공개 저장소에서는 실행되지 않습니다.

---

## 4. 제약 및 주의사항

- **Forge와 NeoForge 동시 구동 불가 (`PLEXUS-COMPAT-221`):**
  MinecraftForge와 NeoForge는 바닐라 바이트코드 패치 및 핵심 클래스 상속 구조가 서로 상충하므로, 두 로더의 모드가 동시에 투입된 경우 Forge가 우선 구동되고 NeoForge 모드는 명확한 사유와 함께 안전하게 제외됩니다.
- **NeoForge 20.4 및 21.1 환경의 Fabric 및 Quilt 모드:**
  Fabric 및 Quilt 모드는 NeoForge 20.4(1.20.4) 및 21.1(1.21.1)과 함께 실행되지 않습니다. FML 부트 계층에 있는 NeoForge 자체 Mixin과 ModList에 대한 추가 적응 작업이 선행되어야 합니다.
- **1.20.1, 1.20.4, 1.21.1의 미검증 게임 창:**
  1.20.1, 1.20.4, 1.21.1의 Forge 및 NeoForge 클라이언트 게임 창은 모드별 중단 문제로 인해 아직 검증되지 않았습니다. Forge 1.21.1에서는 Xaero's World Map이 플레이어 연결을 끊고, Forge 1.20.4에서는 Entity Model Features의 Mixin 디스크립터가 실패하며, Forge 1.20.1 및 NeoForge 21.1에서는 FancyMenu의 LWJGL GLFW 대상 Mixin이 실패하고(모듈 간 `IllegalAccessError`, Plexus 모드 로더 제한), NeoForge 20.4에서는 Entity Texture Features와 BadOptimizations가 모드를 생성하지 못합니다.
- **원격 다운로드 무결성:**
  Forge 및 NeoForge 원본 jar와 FML 의존성 라이브러리는 저작권 보호를 위해 저장소에 포함되지 않으며, 사용자 환경에서 최초 실행 시 공식 Maven에서 받아 SHA-1 체크섬을 엄격히 검증합니다.
