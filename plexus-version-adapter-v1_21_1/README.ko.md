[English](README.md) | [한국어](README.ko.md)

# plexus-version-adapter-v1_21_1

## 이 모듈이 하는 일
Minecraft 1.21.1 전용 지식을 담습니다. 클라이언트/서버 진입점과 내장 변환 규칙(브랜드, 타이틀 화면 모드 수, 레지스트리, 상호작용, 라이프사이클 및 이벤트 훅, 클라이언트 입력·HUD·렌더링·네트워크·화면 훅)이 있습니다.

## 무엇을 소유하는가
`dev.plexusmodloader.game.version.v1_21_1.Minecraft1_21_1Adapter`와 `META-INF/services` 등록.

## 무엇을 하지 않는가
다른 버전을 지원하지 않습니다. 이 모듈 밖에 Minecraft 이름을 두지 않습니다.

## 의존성
`plexus-version-adapter-api`. Minecraft jar에는 컴파일 의존하지 않습니다. 이름은 재매핑된 Mojang 1.21.1 jar와 대조 검증한 문자열입니다.

## 주요 진입점
`Minecraft1_21_1Adapter`

## 빌드·테스트
```powershell
.\gradlew.bat :plexus-version-adapter-v1_21_1:test
```

## 대표 흐름
`plexus:client_brand`(CLIENT)와 `plexus:server_brand`(양쪽)가 `"vanilla"`를 `"plexus"`로 바꿉니다. 실패 정책은 DISABLE_RULE입니다.

`plexus:client_title_mod_counts`(CLIENT, DISABLE_RULE)는 타이틀 화면 `TitleScreen.render`가 버전 글자를 그리는 `GuiGraphics.drawString(Font, String, int, int, int)` 호출을 게임 계층의 `client.TitleScreenModCounts.onVersionText`로 리다이렉트합니다(`RedirectCall`, ADR-0013).

## 1.21.11 / 26.1과 다른 점

Minecraft 1.21.1은 2.0.0부터 정식 지원 버전입니다(ADR-0035). 난독화되어 있어 Mojang 이름으로 재매핑한 게임 jar(ADR-0019)와 Java 25 위에서 동작합니다.
주요 변경 사항:
- 리소스 식별자: 26.x의 `Identifier` 대신 `net.minecraft.resources.ResourceLocation` 사용.
- 네트워크 Configuration 단계가 있습니다(`ServerConfigurationPacketListenerImpl`, `CommonListenerCookie`, `ServerCommonPacketListenerImpl.handleCustomPayload(ServerboundCustomPayloadPacket)`). `PlayerList.placeNewPlayer`도 1.21.11처럼 `(Connection, ServerPlayer, CommonListenerCookie)V`입니다.
- `PlayerList.canPlayerLogin`: `(SocketAddress, GameProfile)Component`.
- `PlayerList.respawn`: `(ServerPlayer, boolean, Entity$RemovalReason)ServerPlayer`.
- `LivingEntity.hurt(DamageSource, float)Z`가 있고 `hurtServer`는 없습니다(1.21.11은 `hurtServer(ServerLevel, DamageSource, float)`).
- `Block.playerWillDestroy`: 반환형이 `BlockState`.
- 크리에이티브 화면 마우스 입력: `mouseClicked(double, double, int)`, `mouseReleased(double, double, int)`, `mouseDragged(double, double, int, double, double)`, `mouseScrolled(double, double, double, double)`.

위 내용은 재매핑한 1.21.1 jar에 `javap`를 실행해 확인했습니다(2026-11-04).

## 클라이언트 규칙

모든 클라이언트 규칙은 `CLIENT` 전용이고 실패 정책이 ABORT_STARTUP입니다(훅 대상이 없으면 기능이 죽은 채 두지 않고 실행을 멈춥니다). 각 규칙은 게임 계층의 `BuiltInRulesTest`가 다시 매핑한 실제 1.21.1 클래스에 적용해 확인했습니다. 1.21.11과 다른 1.21.1 대상:

| 규칙 | 1.21.1 대상 |
| :--- | :--- |
| `plexus:client_key_input` | `KeyboardHandler.keyPress(JIIII)V`(창, 키, 스캔 코드, 동작, 수식 키) |
| `plexus:client_mouse_button` | private `MouseHandler.onPress(JIII)V`(창, 버튼, 동작, 수식 키) |
| `plexus:client_mouse_scroll` | private `MouseHandler.onScroll(JDD)V` |
| `plexus:client_hud` | `Gui.render(GuiGraphics, DeltaTracker)V`. F1은 `Options.hideGui`로 HUD를 숨김 |
| `plexus:client_shader_uniforms` | `LevelRenderer.renderLevel(...)V`의 머리(`GlRenderPass.setPipeline`이 없음) |
| `plexus:client_world_painters` | `LevelRenderer.renderLevel(...)V`의 반환 직전(`submitEntities`가 없음) |
| `plexus:client_fov` | `GameRenderer.getFov(Camera, float, boolean)D`(`float`이 아니라 `double`) |
| `plexus:client_particle_providers` | `ParticleEngine.registerProviders()V`(`ParticleResources`는 1.21.11) |
| `plexus:client_block_entity_renderers` | `BlockEntityRenderers.createEntityRenderers(...)Map`(렌더러가 바로 그리며 렌더 상태가 없음) |
| `plexus:client_item_tooltip` | `ItemStack.getTooltipLines(Item$TooltipContext, Player, TooltipFlag)List`. 두 번 반환하며 첫 번째는 `HIDE_TOOLTIP` |
| `plexus:client_left_world` | `Minecraft.disconnect(Screen, Z)V`(1.21.11은 boolean이 하나 더 있음) |
| `plexus:client_payloads`, `plexus:client_configuration_payloads` | `ClientPacketListener`, `ClientConfigurationPacketListenerImpl`의 `.handleCustomPayload(CustomPacketPayload)V` |
| `plexus:client_menu_screens` | `MenuScreens.create(MenuType, Minecraft, int, Component)V` |

## 제한

- 아직 옮기지 못한 클라이언트 기능은 없습니다. 모든 클라이언트 선언과 `ClientEvents` 키에 규칙이 있습니다. 남은 차이는 게임 계층의 `제한`에 있습니다(셰이더 방언과 위치, 후처리 효과 체인 위치, 점을 정육면체로 그림, 리소스 리로드로 셰이더 파이프라인을 다시 만들지 않음).
- 클라이언트 규칙 중 게임 창에서 실행한 것은 없습니다. 게임 계층의 `제한`을 보세요.
