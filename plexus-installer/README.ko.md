[English](README.md) | [한국어](README.ko.md)

# plexus-installer — Cross-Platform Installer & Launcher Integrator

`plexus-installer`는 마인크래프트 공식 런처(`.minecraft`) 및 독립 서버 환경에 Plexus Mod Loader를 설치·관리할 수 있는 크로스 플랫폼 설치 프로그램 모듈입니다.

---

## 1. 배포 형태 및 아키텍처

Plexus 인스톨러는 모든 플랫폼에서 jar 하나로 빌드 및 배포됩니다:

| 배포 파일명 | 대상 플랫폼 | 특징 |
| :--- | :--- | :--- |
| **`PlexusModLoader-<version>.jar`** | Windows, macOS, Linux (Java 17+) | Java Swing GUI 기반 크로스 플랫폼 인스톨러. CLI 헤드리스 설치 지원. |

---

## 2. 주요 기능

1. **공식 마인크래프트 런처 원클릭 프로필 생성:**
   - `.minecraft/versions` 및 `launcher_profiles.json`에 `Plexus Mod Loader <버전>` 프로필을 안전하게 추가합니다.
   - 기존 설정 손상을 방지하기 위해 원자적 파일 쓰기(Atomic Write) 및 백업(`.plexusmodloader-backup`)을 수행합니다.
2. **다국어 GUI 지원 (영어 / 한국어):**
   - 시스템 언어를 자동 감지하며, 창 우측 상단의 언어 메뉴를 통해 실시간으로 전환할 수 있습니다.
3. **독립 서버 원클릭 설치 (명령줄 `install-server`):**
   - 전용 서버(Dedicated Server) 실행에 필요한 라이브러리, 구동 스크립트, 모드 폴더(`mods/`)를 지정된 디렉터리에 자동으로 구성합니다.
4. **최적 메모리 할당 자동 계산:**
   - 시스템 물리 메모리 용량을 감지하여 추천 JVM 힙 메모리(`-Xmx`)를 프로필에 자동 설정합니다.

---

## 3. 제공하는 Minecraft 버전

설치 창, `--minecraft`, 릴리스의 `Minecraft:` 줄이 모두 같은 목록을 씁니다. 이 목록은 jar 안의 `installer.json`이고, 빌드가 `gradle/minecraft-versions.properties`에서 가져옵니다(`supported` 버전과 그 핫픽스). 실험 버전은 제공하지 않습니다. 목록에 없는 버전은 아무것도 내려받거나 쓰기 전에 `does not support Minecraft <버전>; it supports ...` 오류로 실패합니다(종료 코드 1).

### 26.1 이전 버전(난독화: 1.20.1, 1.20.4, 1.21.1, 1.21.11 등)

게임 jar가 짧은 난독화 이름을 쓰므로, 설치 프로그램이 `install`과 `install-server` 모두에서 **플레이어 PC에서** Mojang 이름으로 다시 매핑합니다(ADR-0019).

1. Mojang 버전 JSON의 주소에서 버전 데이터, 게임 jar, Mojang 공식 매핑(`client_mappings` 또는 `server_mappings`)을 내려받고 각각 Mojang이 적은 SHA-1로 검증합니다. 파일이 없거나 깨졌거나 달라졌으면 메시지와 함께 멈추고, 이때 Plexus Mod Loader 파일은 쓰지 않습니다.
2. ASM으로 jar를 다시 매핑하고(약 5~15초) 결과를 검사합니다(남은 난독화 클래스 이름 없음, 중복 멤버 없음, `version.json` 유지). 검사에 실패하면 설치가 실패합니다.
3. jar의 SHA-1, 매핑의 SHA-1, 리매퍼 리비전 기준으로 캐시하므로 다시 설치하면 캐시된 jar를 씁니다.

파일 위치:

| 설치 | 매핑과 다시 매핑한 jar | 게임이 쓰는 방식 |
| :--- | :--- | :--- |
| `install`(런처) | 런처 폴더의 `libraries/dev/plexusmodloader/minecraft-mojang/<버전>/` | 프로필 버전이 다시 매핑한 jar를 라이브러리로 올리고 `-Dplexus.gameJar=${library_directory}/...`를 넘깁니다. 런처에는 Java 25(`javaVersion`)도 요청합니다. Mojang 원본 jar와 버전 JSON은 런처가 두는 `versions/<버전>/`에, 런처가 어차피 받는 라이브러리는 `libraries/`에 받습니다. |
| `install-server` | 서버 폴더의 `versions/<버전>/` | `plexusmodloader-server.jar`의 class path에 Mojang jar 대신 다시 매핑한 jar가 들어갑니다. |

매핑과 다시 매핑한 jar는 Minecraft 코드이고 Mojang의 파일입니다. 설치 프로그램, 저장소, 릴리스에 넣지 않으며 `uninstall`은 설치 프로그램이 만든 것만 지웁니다. 26.1 이후 버전은 난독화되지 않아 설치 때 내려받을 것이 없고 전과 같이 설치합니다.

다시 매핑에는 메모리가 많이 들지 않습니다(측정: 1.21.11 클라이언트 설치가 `java -Xmx256m -jar ...`로 끝남). 메모리 부족 오류가 나면 `java -Xmx2G -jar PlexusModLoader-<버전>.jar`처럼 더 크게 주고 실행하세요.

---

## 4. 빌드 및 테스트 (Gradle Tasks)

```powershell
# 단위 테스트 실행
.\gradlew.bat :plexus-installer:check

# 배포용 jar 빌드 (plexus-installer/build/distributions/)
.\gradlew.bat :plexus-installer:installerDistributions

# 모의 런처 프로필 생성 및 구동 통합 테스트
.\gradlew.bat :plexus-bootstrap:launcherProfileTest

# 헤드리스 서버 설치 및 기동 테스트 (다른 제공 버전은 -PserverInstallMinecraft=<버전>;
# 26.1 이전 버전은 해당되는 단계를 실행: 다시 매핑한 서버가 EULA 확인까지 도달, 거부된 규칙 0개)
.\gradlew.bat :plexus-installer:serverInstallTest
```

---

## 5. 모듈 구조

- **`dev.plexusmodloader.installer`**: Java Swing GUI 창, CLI 파서, 프로필 JSON 생성기, 라이브러리 검증기, 그리고 26.1 이전 버전용 다운로드(`VerifiedDownloads`), Mojang 매핑 파서(`MojangMappings`), ASM 리매퍼(`MojangJarRemapper`), 캐시와 검사(`RemappedGameJar`, `ClientGameRemap`).
- 아이콘은 `ProfileIcon`이 코드로 그리고, 영어·한국어 문구는 `InstallerWindow.Language`에 있습니다(별도 리소스 파일 없음).
