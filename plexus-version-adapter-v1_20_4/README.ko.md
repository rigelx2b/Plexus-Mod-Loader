[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-v1_20_4

## 이 모듈이 하는 일
Minecraft 1.20.4 전용 지식을 담습니다. 클라이언트/서버 진입점과 내장 변환 규칙(브랜드, 타이틀 화면 모드 수, 레지스트리, 상호작용, 생명주기, 네트워크 페이로드, 설정 단계, 이벤트 훅)이 있습니다.

## 무엇을 소유하는가
`dev.plexusmodloader.game.version.v1_20_4.Minecraft1_20_4Adapter`와 `META-INF/services` 등록.

## 무엇을 하지 않는가
다른 버전을 지원하지 않습니다. 이 모듈 밖에 Minecraft 이름을 두지 않습니다.

## 의존성
`plexus-version-adapter-api`. Minecraft jar에는 컴파일 의존하지 않습니다. 이름은 재매핑된 Mojang 1.20.4 jar와 대조 검증한 문자열입니다.

## 주요 진입점
`Minecraft1_20_4Adapter`

## 빌드·테스트
```powershell
.\gradlew.bat :plexus-version-adapter-v1_20_4:test
.\gradlew.bat :plexus-game-v1_20_4:test "-PplexusMinecraft=1.20.4"   # BuiltInRulesTest가 모든 규칙을 실제 1.20.4 클래스에 적용
```

## 대표 흐름
`plexus:client_brand`(CLIENT)와 `plexus:server_brand`(양쪽)가 `"vanilla"`를 `"plexus"`로 바꿉니다. 실패 정책은 DISABLE_RULE입니다.

`plexus:client_title_mod_counts`(CLIENT, DISABLE_RULE)는 타이틀 화면 `TitleScreen.render`가 버전 글자를 그리는 `GuiGraphics.drawString(Font, String, int, int, int)` 호출을 게임 계층의 `client.TitleScreenModCounts.onVersionText`로 리다이렉트합니다(`RedirectCall`, ADR-0013).

`plexus:payload_read_serverbound`와 `plexus:payload_read_clientbound`(양쪽, ABORT_STARTUP)는 두 사용자 지정 페이로드 패킷의 비공개 static `readPayload(ResourceLocation, FriendlyByteBuf)` 머리에서 `GameHooks.onReadPayload`를 실행합니다. 게임은 자기 페이로드만 알고 나머지를 버리므로, 이 훅이 모드가 선언한 채널의 페이로드를 돌려주고 그 밖에는 `null`을 돌려줍니다.

`plexus:configuration_tasks`와 `plexus:configuration_tick`은 모드의 설정 단계 교환을 설정 작업으로 실행하게 합니다. 1.20.4의 `ConfigurationTask`에는 자체 tick이 없어서, 리스너의 `tick`이 기다리는 교환을 확인합니다.

## 1.21.11 / 26.1과 다른 점

Minecraft 1.20.4은 Mojang 이름으로 재매핑한 jar와 Java 25 위에서 동작합니다(ADR-0019, ADR-0035).
주요 변경 사항:
- 리소스 식별자: 26.x의 `Identifier` 대신 `net.minecraft.resources.ResourceLocation` 사용.
- 네트워크 Configuration 단계가 있습니다(`ServerConfigurationPacketListenerImpl`, `CommonListenerCookie`, `ServerCommonPacketListenerImpl`). `PlayerList.placeNewPlayer`는 `(Connection, ServerPlayer, CommonListenerCookie)V`입니다.
- 사용자 지정 페이로드는 ID와 `write(FriendlyByteBuf)`를 가진 `CustomPacketPayload` 레코드입니다. `StreamCodec`과 `CustomPacketPayload.codec`이 없습니다.
- `PlayerList.canPlayerLogin`: `(SocketAddress, GameProfile)Component`.
- `PlayerList.respawn`: `Entity$RemovalReason` 없이 `(ServerPlayer, boolean)ServerPlayer`(1.21.1에는 있음).
- `LivingEntity.hurt(DamageSource, float)Z`가 있고 `hurtServer`는 없습니다.
- `Block.playerWillDestroy`: 반환형이 `BlockState`(1.20.1은 `void`).
- 크리에이티브 화면 마우스 입력: `mouseClicked(double, double, int)`, `mouseReleased(double, double, int)`, `mouseDragged(double, double, int, double, double)`, `mouseScrolled(double, double, double, double)`.

위 내용은 재매핑한 1.20.4 jar에 `javap`를 실행해 확인했습니다(2026-11-04).

## 제한

- 클라이언트 규칙(`plexus:client_*`: 키 바인딩, 키·마우스 버튼·휠 입력, 틱, FOV, 접속·퇴장, HUD, 클라이언트·설정 단계 페이로드, 메뉴 화면, 파티클, 블록 엔티티·엔티티 렌더러, 에셋 리로드, 툴팁, 월드 페인터, 크리에이티브 탭 페이지)은 `BuiltInRulesTest`가 실제 1.20.4 클래스에 대해 계획·적용하고 훅을 찾는지 검사하며 `Minecraft1_20_4AdapterTest`에 목록이 있지만, 창을 연 적은 없습니다. 실행 중인 클라이언트에서의 동작은 아직 검증하지 않았습니다.
- 26.x의 초기 창 제목·로딩 오버레이 규칙과 렌더 파이프라인 규칙(`plexus:client_shader_uniforms`)은 1.20.4에 없습니다. 모드 셰이더 파이프라인은 게임 계층이 프로그램 파일을 만들어 GLSL 1.50 core 방언으로 구성합니다(`plexus-game-v1_20_4` README 참고).
- `plexus:client_world_painters`는 `LevelRenderer.renderLevel`의 끝(모든 return)에서, `plexus:client_hud`는 `Gui.render(GuiGraphics, float)`의 끝에서 실행됩니다.
