[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-v1_20_1

## 이 모듈이 하는 일
Minecraft 1.20.1 전용 지식을 담습니다. 클라이언트/서버 진입점과 내장 변환 규칙(브랜드, 타이틀 화면 모드 수, 레지스트리, 상호작용, 라이프사이클 및 이벤트 훅)이 있습니다.

## 무엇을 소유하는가
`dev.plexusmodloader.game.version.v1_20_1.Minecraft1_20_1Adapter`와 `META-INF/services` 등록.

## 무엇을 하지 않는가
다른 버전을 지원하지 않습니다. 이 모듈 밖에 Minecraft 이름을 두지 않습니다.

## 의존성
`plexus-version-adapter-api`. Minecraft jar에는 컴파일 의존하지 않습니다. 이름은 재매핑된 Mojang 1.20.1 jar와 대조 검증한 문자열입니다.

## 주요 진입점
`Minecraft1_20_1Adapter`

## 빌드·테스트
```powershell
.\gradlew.bat :plexus-version-adapter-v1_20_1:test
```

## 대표 흐름
`plexus:client_brand`(CLIENT)와 `plexus:server_brand`(양쪽)가 `"vanilla"`를 `"plexus"`로 바꿉니다. 실패 정책은 DISABLE_RULE입니다.

`plexus:client_title_mod_counts`(CLIENT, DISABLE_RULE)는 타이틀 화면 `TitleScreen.render`가 버전 글자를 그리는 `GuiGraphics.drawString(Font, String, int, int, int)` 호출을 게임 계층의 `client.TitleScreenModCounts.onVersionText`로 리다이렉트합니다(`RedirectCall`, ADR-0013).

## 1.21.11 / 26.1과 다른 점

**정식 지원 버전입니다**(ADR-0035. 다시 매핑한 jar는 ADR-0019 참고). Minecraft 1.20.1은 Mojang 이름으로 재매핑한 jar와 Java 25 위에서 동작합니다.
주요 변경 사항:
- 리소스 식별자: 26.x의 `Identifier` 대신 `net.minecraft.resources.ResourceLocation` 사용.
- 네트워크 Configuration 단계: 1.20.1에는 `ServerConfigurationPacketListenerImpl` 및 `CommonListenerCookie`가 없으므로 `ServerboundCustomPayloadPacket`을 직접 다룹니다.
- `PlayerList.placeNewPlayer`: 3인자 cookie 없이 `(Connection, ServerPlayer)V`.
- `PlayerList.canPlayerLogin`: `(SocketAddress, GameProfile)Component`.
- `PlayerList.respawn`: `RemovalReason` 없이 `(ServerPlayer, boolean)ServerPlayer`.
- `LivingEntity.hurt`: 1.21에서 추가된 `hurtServer` 대신 `hurt(DamageSource, float)Z` 사용.
- `Block.playerWillDestroy`: 반환형이 `void`.
- 크리에이티브 인벤토리 마우스 입력: 이벤트 객체 대신 `(double, double, int)Z` 시그니처 사용.

## 제한

- 1.20.1에는 설정 단계가 없어서, 이후 버전의 설정 단계 페이로드·작업 규칙 대신 로그인 쿼리 규칙(`plexus:login_exchange_hold`, `plexus:login_exchange_answer`, `plexus:client_login_query`)을 씁니다.
