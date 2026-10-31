# plexus-fabric-api-neoforge-v26_2 — NeoForge 26.2용 Fabric API 호스트 모듈

Plexus Mod Loader의 Mixin 브리지로도 NeoForge 옆에서 원래대로 실행할 수 없는 Fabric API 모듈을 위해, `NeoForge 26.2.0.93`의 훅 위에서 동작하는 자체 Fabric API 구현체("호스트 모듈", `ADR-0031`)를 빌드합니다.

## 이 모듈이 하는 일
- `NeoForge 26.2.0.93`의 훅 위에서 Plexus Mod Loader 자체의 Fabric API 모듈("호스트 모듈", `ADR-0031`)을 빌드합니다.
- Plexus Mod Loader의 Mixin 브리지로도 NeoForge 옆에서 원래대로 실행할 수 없는 모듈을 대체합니다.
- 현재는 Fabric API `0.161.0+26.2`의 같은 모듈과 공개 API가 같은 열네 모듈을 포함합니다: `fabric-block-api-v1`(`3.1.0+plexus.neoforge.26.2`), `fabric-item-api-v1`(`14.5.0+plexus.neoforge.26.2`), `fabric-registry-sync-v0`(`7.1.1+plexus.neoforge.26.2`), `fabric-object-builder-api-v1`(`24.1.1+plexus.neoforge.26.2`), `fabric-content-registries-v0`(`11.3.2+plexus.neoforge.26.2`), `fabric-data-generation-api-v1`(`25.6.0+plexus.neoforge.26.2`), `fabric-menu-api-v1`(`2.0.17+plexus.neoforge.26.2`), 클라이언트 모듈 `fabric-screen-api-v1`(`5.2.1+plexus.neoforge.26.2`), `fabric-sound-api-v1`(`2.0.5+plexus.neoforge.26.2`), `fabric-rendering-fluids-v1`(`6.0.4+plexus.neoforge.26.2`), `fabric-rendering-v1`(`25.3.3+plexus.neoforge.26.2`), `fabric-renderer-api-v1`(`14.1.4+plexus.neoforge.26.2`), `fabric-renderer-indigo`(`9.1.4+plexus.neoforge.26.2`, `always`), `fabric-model-loading-api-v1`(`8.0.17+plexus.neoforge.26.2`).
- 소스는 Sinytra Forgified Fabric API(https://github.com/Sinytra/ForgifiedFabricAPI, 브랜치 `26.2.x`, 커밋 `40f8ce0`)의 같은 이름 모듈입니다. 이는 Fabric API(`FabricMC`, https://github.com/FabricMC/fabric) 모듈에 NeoForge 전용 변경을 적용한 것입니다.
- 블록 모듈 변경 사항은 `Block`/`BlockState` 대신 NeoForge의 `IBlockExtension` 및 `IBlockStateExtension` 대상 Mixin, Fabric과 NeoForge 마법 부여 위력 간의 `EnchantmentPowerBridge`, NeoForge `FluidInteractionRegistry.canInteract` 대상 `LiquidBlockMixin`, NeoForge `EventHooks.onEnchantmentLevelSet` 대상 `EnchantmentMenuMixin`입니다.
- 아이템 모듈의 Forgified Fabric API 자체 변경 사항은 NeoForge의 `IItemExtension` 및 `IItemStackExtension` 대상 Mixin(`IItemExtensionMixin`, 클라이언트 `IItemExtensionClientMixin`), NeoForge `ItemTooltipHandler`를 통한 툴팁 처리, NeoForge 훅 기반 모루 및 마법 부여 검사(`AnvilMenuMixin`, `EnchantCommandMixin`, `EnchantRandomlyFunctionMixin`)입니다.
- 아이템 모듈에 대한 Plexus Mod Loader의 변경 사항(서식 정리 제외):
  - Forgified Fabric API의 `FabricItemImpl`(NeoForge `@Mod` 클래스)을 제거하고, 새 Mixin `ItemTooltipHandlerMixin`으로 NeoForge `ItemTooltipHandler.init` 내부의 `ModLoader.postEvent` 호출을 감싸 이벤트 게시 후 `ItemComponentTooltipProviderRegistryImpl`을 등록합니다.
  - `ClientItemEventHooks`는 `NeoForge.EVENT_BUS`에 리스너를 등록하는 Fabric `ClientModInitializer`(클라이언트 진입점)입니다.
  - `DataComponentMapBuilderMixin`은 NeoForge의 `IDataComponentMapBuilderExtensions`와 `DataComponentGetter`가 모두 기본 메서드를 제공하므로 `getOrDefault`를 직접 선언합니다.
  - `IItemExtensionMixin.isPrimaryItemFor`는 명시적인 `EnchantmentEvents.ALLOW_ENCHANTING` 응답을 먼저 반영합니다.
- 각 파일의 헤더에 소스 파일과 커밋, Plexus Mod Loader의 변경 내용이 명시되어 있습니다.
- 라이선스는 `Apache License 2.0`이며, jar는 `LICENSE_fabric-api`를 담습니다(두 프로젝트의 텍스트가 같으며 `NOTICE` 파일은 없음).

- registry-sync 모듈: FFAPI의 같은 모듈에서 `FabricRegistryInit`의 NeoForge 모드 버스 리스너를 빼고, 새 `RegistryManagerMixin`이 NeoForge `RegistryManager.postNewRegistryEvent` 안에서 `DataPackRegistryEvent.NewRegistry.process()`와 `ModifyRegistriesEvent` 게시를 감쌉니다.
- object-builder 모듈: FFAPI의 `FabricObjectBuilderImpl`(NeoForge `@Mod` 클래스, `FMLCommonSetupEvent`·`EntityAttributeModificationEvent` 리스너)을 빼고, 새 `GameDataMixin`이 NeoForge 등록(`GameData.postRegisterEvents`)과 Fabric 모드 초기화가 모두 끝난 뒤의 `GameData.freezeData`에서 속성 수정(`FabricDefaultAttributeRegistry.MODIFY`)과 추가된 유효 블록(`FabricBlockEntityType.addValidBlock`)을 적용합니다. 서버에서는 Fabric 모드 초기화가 NeoForge 등록보다 먼저이고, 클라이언트에서는 나중입니다. `FabricDefaultAttributeRegistryImpl`은 `DefaultAttributes.getSupplier`가 먼저 읽는 NeoForge 맵(`CommonHooks.FORGE_ATTRIBUTES`, 새 `CommonHooksAccessor`)의 값을 고칩니다. 원본의 표지판 편집 화면 클라이언트 mixin 2개는 FFAPI처럼 넣지 않습니다(NeoForge 패치가 `Identifier.withDefaultNamespace` 대신 `Identifier.parse`를 써서 모드 나무 종류를 이미 처리함).
- content-registries 모듈: FFAPI는 퇴비(`CompostableRegistry`)와 영웅 선물(`VillagerInteractionRegistries.registerGiftLootTable`)을 NeoForge 데이터맵 조회(`BaseMappedRegistry.getData`, 새 `BaseRegistryMixin`·`DataMapModifications`)로, 연료를 NeoForge `DataMapHooks.populateFuelValues`(`DataMapHooksMixin`)로, 도끼 벗기기를 NeoForge `AxeItem.getAxeStrippingState`로, 괭이 경작을 NeoForge `BlockToolModificationEvent`로 옮겼고, 유체의 수영·익사·점프·이동은 NeoForge `FluidType`에 맡깁니다(`EntityFluidInteraction`의 `FluidType`별 흐름을 태그로 묶는 `ContentRegistriesImpl`, `EntityFluidInteractionMixin`). Plexus Mod Loader는 FFAPI의 `@Mod` 클래스 `ContentRegistriesImpl`을 Fabric `main` 진입점으로 바꾸고, FML이 이 모듈을 읽지 않으므로 `TillableBlockRegistry`의 `@EventBusSubscriber` 대신 그 진입점이 `NeoForge.EVENT_BUS.register(TillableBlockRegistry.class)`로 리스너를 등록합니다. FFAPI가 `fabric-api-base`로 옮긴 `Block2ObjectMap`·`Item2ObjectMap`은 Fabric API의 원래 자리(이 모듈)에 둡니다. Plexus Mod Loader 옆의 `fabric-api-base`는 Fabric API의 것이라 이 둘이 없습니다.
- data-generation 모듈: NeoForge가 `TagAppender`·`BlockItemTagAppender`·`TagBuilder`에 같은 이름의 `remove`를 더하므로, FFAPI는 태그 제거를 구현하던 mixin(`BlockItemTagAppenderMixin`, `TagAppenderMixin$TagAppender1Mixin`, `TagBuilderMixin`, `TagsProviderMixin`의 제거·교체 주입)을 빼고 `FabricTagAppender`가 NeoForge `ITagAppenderExtension`을 상속하게 합니다. 레시피 출력은 NeoForge의 조건 인자를 받는 `RecipeOutput.accept`로, 모델 생성기는 NeoForge의 `ModelProvider.registerModels`로 감쌉니다. Plexus Mod Loader는 FFAPI에서 아직 "Implemented via mixin" 예외를 던지던 `FabricTagAppender.remove(ResourceKey...)`·`removeAll`·`removeTag`를 NeoForge의 `remove`로 구현합니다.
- menu 모듈: NeoForge는 `ServerPlayer.openMenu(MenuProvider)`의 본문을 `openMenu(MenuProvider, Consumer)`로 옮기고, 추가 데이터가 있으면 자체 `AdvancedOpenScreenPayload`로 보내 클라이언트에서 메뉴 종류의 `IContainerFactory`가 받습니다. Fabric API의 `ServerPlayerMixin`(메뉴 저장 `@Inject`, `closeContainer`·패킷 `@Redirect`)은 옮겨진 본문에 맞지 않아 `224`였습니다. FFAPI는 Fabric의 자체 열기 패킷(`Networking`, 클라이언트 `ClientNetworking`)을 빼고, `openMenu(MenuProvider)`에서 `SimpleMenuProvider`에 싸인 `ExtendedMenuProvider`를 꺼내며(`@ModifyArg`), `openMenu(MenuProvider, Consumer)`에서 메뉴를 만든 뒤 추가 데이터 쓰기를 메뉴 종류의 코덱으로 `getScreenOpeningData`를 쓰는 것으로 바꿉니다(`@ModifyVariable`). `ExtendedMenuType`은 NeoForge `IContainerFactory`로 데이터를 읽어 메뉴를 만들고, `MenuProviderMixin`은 `shouldCloseCurrentScreen`을 NeoForge의 `shouldTriggerClientSideContainerClosingOnOpen`에 잇습니다. Plexus Mod Loader는 클래스 경로에 없는 `javax.annotation.Nullable`을 jspecify의 것으로 바꿨습니다.
- screen 모듈: NeoForge는 화면을 `ClientHooks.extractScreen`으로 그리고 키보드·마우스 입력을 자기 `ScreenEvent`로 알립니다. Fabric API의 `GuiMixin`(`Gui.extractRenderState`의 `Screen.extractRenderStateWithTooltipAndSubtitles` 감싸기)·`KeyboardHandlerMixin`·`MouseHandlerMixin`은 맞지 않아 `224`였습니다. FFAPI는 그 mixin을 빼고 `ScreenEventHooks`가 NeoForge `ScreenEvent`(그리기, 키, 마우스)를 Fabric 이벤트로 옮깁니다. FML은 Fabric 모드의 `@EventBusSubscriber`를 읽지 않으므로 Plexus Mod Loader는 그 표시를 빼고 새 클라이언트 진입점 `ScreenApiClientInit`이 `NeoForge.EVENT_BUS.register(ScreenEventHooks.class)`로 등록합니다.
- sound 모듈: NeoForge는 `SoundEngine.play`에서 `SoundBufferLibrary.getStream` 대신 자기 기본 메서드 `SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)`을 부릅니다. FFAPI의 `SoundInstanceMixin`이 그 기본 메서드를 `@Overwrite`해 `FabricSoundInstance.getAudioStream`에 넘깁니다.
- rendering-fluids 모듈: NeoForge는 `FluidRenderer.tesselate`의 `HalfTransparentBlock` 검사를 `BlockState.shouldDisplayFluidOverlay`로 바꿉니다. FFAPI의 `FluidRendererMixin`은 그 호출의 결과를 `FluidRenderingRegistryImpl.isBlockTransparent(block, original)`로 바꿉니다(`javap`로 26.2.0.93의 호출과 지역 변수 `faceState` 확인).
- rendering·renderer-api·indigo·model-loading 모듈: NeoForge는 HUD를 자기 GUI 레이어로 그리고, 화면 속 화면(picture-in-picture) 렌더러와 블록 모델을 자기 이벤트·확장으로 등록하며, Fabric API가 감싸는 `BlockStateModel.collectParts`·`hasMaterialFlag`·`SectionCompiler.compile`에 다른 매개변수를 줍니다. FFAPI의 같은 모듈을 쓰고 Plexus Mod Loader는 다음을 바꿨습니다.
  - FFAPI의 `@Mod` 클래스 `FabricRenderingImpl`을 빼고, 새 mixin `ClientHooksMixin`(`ClientHooks.gatherPictureInPictureRenderers`)과 `GuiLayerManagerMixin`(`GuiLayerManager.initModdedLayers`)이 `ModLoader.postEvent` 호출을 감싸 NeoForge 모드가 이벤트를 받은 뒤 Fabric의 등록을 더합니다.
  - `RenderPipelineBuilderMixin`은 `Snippet` 생성자의 `@WrapOperation` 대신 `buildSnippet` 전체를 `@WrapMethod`로 감쌉니다(호스트 검사가 생성자 서술자 없는 `NEW`를 처리기와 상관없이 맞지 않는다고 보고, 서술자를 적으면 실행 때 Mixin이 주입 지점을 찾지 못함).
  - `SubmitNodeStorageMixin`은 `SubmitNodeCollector`를 구현하지 않고 `order(int)`를 `@Shadow`합니다. NeoForge의 `SubmitNodeStorageExtension`이 이미 `SubmitNodeCollector`를 상속하므로 같은 메서드이고, 호스트 검사의 기본 메서드 충돌 판정을 피합니다.
  - `VanillaBlockModelPartEncoder`는 FFAPI가 자기 `fabric-api-base`에 더한 `TriState.fromVanilla`를 자기 메서드로 둡니다.
  - `HudElementRegistryImpl`은 Fabric의 `INFO_BAR`를 NeoForge `CONTEXTUAL_INFO_BAR_BACKGROUND`에 잇습니다(`javap`로 `Hud.registerVanillaLayers` 확인).
  - indigo는 원본이 renderer-api가 바뀌면 로드되지만 색인에 `always`로 표시해 대체합니다. renderer-api의 `SectionCompilerMixin`이 레벨의 모든 블록을 Indigo로 보내는데, FFAPI의 Indigo만 NeoForge의 레벨 기준 면 컬링, 빛 방출, 동적 틴트, 구운 정점 색을 지킵니다. 근거는 테스트 줄이 아니라 코드 비교입니다(ADR-0031 후속).
- 빌드는 호스트 모듈이 의존하는 다른 호스트 모듈의 `transitive-*` class tweaker 줄과, rendering·renderer-api에는 Fabric API `fabric-transitive-access-wideners-v1`(`8.1.4+67c847259e`)의 class tweaker를 컴파일할 때 적용합니다(Loom과 같음).- 원본 object-builder는 NeoForge 옆에서 `224` 없이 로드되지만 속성 수정이 게임에 닿지 않습니다(테스트 모드 `fabric-object-builder`: 좀비 최대 체력 20.0, Fabric 단독은 42.0). 그래서 색인에 `always`로 표시해 원본이 로드되더라도 대체합니다.

## 무엇을 소유하는가
- 소스 세트 구조: `src/fabricBlockApiV1`, `src/fabricItemApiV1`, `src/fabricRegistrySyncV0`, `src/fabricObjectBuilderApiV1`, `src/fabricContentRegistriesV0`, `src/fabricDataGenerationApiV1`, `src/fabricMenuApiV1`, `src/fabricScreenApiV1`, `src/fabricSoundApiV1`, `src/fabricRenderingFluidsV1`, `src/fabricRenderingV1`, `src/fabricRendererApiV1`, `src/fabricRendererIndigo`, `src/fabricModelLoadingApiV1`. renderer-api는 rendering에, indigo와 model-loading은 rendering·renderer-api에 대해 컴파일합니다. data-generation도 registry-sync 소스 세트에 대해 컴파일합니다. object-builder는 registry-sync 소스 세트의 출력에 대해 컴파일합니다(`HostModule.dependsOn`).
- Fabric 모드 jar: 각 모듈의 `fabric.mod.json`, Mixin 설정(`fabric-block-api-v1.mixins.json`, `fabric-item-api-v1.mixins.json`, `fabric-item-api-v1.client.mixins.json`), 클래스 트위커(`fabric-block-api-v1.classtweaker`, `fabric-item-api-v1.classtweaker`).
- `fabric.mod.json`의 `custom` 필드 `plexusmodloader:host-module` (`host` `neoforge`, `minecraft` `26.2`, `replaces` 각 모듈 버전).
- NeoForge 훅에 맞춘 구현 소스 코드: `fabric-block-api-v1` 및 `fabric-item-api-v1` 구현 코드(`net.fabricmc.fabric.api.block.v1.*`, `net.fabricmc.fabric.api.item.v1.*`, `net.fabricmc.fabric.api.client.item.v1.*`, `net.fabricmc.fabric.impl.*`, `net.fabricmc.fabric.mixin.*`).
- Gradle 빌드 작업: `installNeoForgeClient`, `<sourceSet>CompileGame`, `<sourceSet>Jar`, `hostModuleIndex`, `hostModuleResources`.
- 빌드 산출물 jar(`META-INF/plexusmodloader/host-modules/neoforge-26.2.0.93/` 아래의 열네 jar)와 색인 파일 `META-INF/plexusmodloader/host-modules.properties`(키 `neoforge-26.2.0.93.<모드 ID>`, 원본이 로드돼도 대체하는 모듈은 `neoforge-26.2.0.93.<모드 ID>.always=true`).
- `plexus-bridge-fabric`에 산출물을 전달하는 소모성 구성 `hostModuleElements`.

## 무엇을 하지 않는가
- NeoForge가 없거나 다른 NeoForge 릴리스에서는 아무것도 하지 않습니다.
- `MinecraftForge`를 위한 것이 아닙니다.
- Forgified Fabric API를 다운로드하거나 요구하지 않습니다.
- 브리지로 이미 실행되는 모듈은 다시 구현하지 않습니다.
- Plexus Mod Loader의 클래스 경로에 jar를 직접 올리지 않습니다.

## 의존성
- Minecraft `26.2` (`plexus.minecraft-compile`, 모듈의 `gradle.properties`에서 `plexusCompileMinecraft=26.2`로 고정).
- NeoForge의 패치된 클라이언트 jar: `installNeoForgeClient` 작업(`NeoForgeClientInstallTask`)이 `NeoForge 26.2.0.93` 자체 설치 프로그램(SHA-1 `97205829a020fc9389f504a77f590b54157c8dde` 검사)을 `--installClient`로 `~/.gradle/caches/plexus-neoforge/26.2.0.93/client`에 실행(첫 사용 시 네트워크 필요)하여 생성되는 `libraries/net/neoforged/minecraft-client-patched/26.2.0.93/minecraft-client-patched-26.2.0.93.jar`를 기반으로 합니다.
- `ClassTweakedJarTask`(`<sourceSet>CompileGame`) 작업이 컴파일 시점에만 모듈의 클래스 트위커(`accessible`, `extendable`, `mutable`, `inject-interface`)를 이 jar에 적용합니다.
- 기타 `compileOnly` 입력: NeoForge universal jar, Fabric Loader, Mixin, MixinExtras, `fabric-api-base` `2.0.4+ece063239e`, `fabric-resource-loader-v1` `2.0.13+9edec1269e`, `fabric-lifecycle-events-v1` `4.1.4+29b6eb019e`, `fabric-tag-api-v1` `2.1.4+515ac5339e`, `fabric-convention-tags-v2` `4.7.1+1f023a9e9e`, `fabric-recipe-api-v1` `9.0.22+11e1c9a39e`, `fabric-resource-conditions-api-v1` `6.1.0+10e9a28b9e`, `jspecify`, JetBrains annotations, NeoForge의 이벤트 버스, FML loader 및 mergetool API.

## 주요 진입점
- `fabric-block-api-v1` 공개 API 진입점: `net.fabricmc.fabric.api.block.v1` 패키지의 `BlockFunctionalityTags`, `FabricBlock`, `FabricBlockState`, `FluidFlowEvents`.
- `fabric-item-api-v1` 공개 API 진입점: `net.fabricmc.fabric.api.item.v1` 패키지의 `FabricItem`, `FabricItemStack`, `EnchantmentEvents`, `DefaultItemComponentEvents`, `CustomDamageHandler`, `ItemComponentTooltipProviderRegistry`, 그리고 `net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback`.
- 클라이언트 진입점: `ClientItemEventHooks`(`ClientModInitializer`).
- 내부 훅 브리지: `EnchantmentPowerBridge`.
- registry-sync·object-builder 공개 API 진입점: `net.fabricmc.fabric.api.event.registry`(`DynamicRegistries`, `FabricRegistryBuilder` 등), `net.fabricmc.fabric.api.object.builder.v1`(`FabricDefaultAttributeRegistry`, `FabricEntityDataRegistry`, `FabricBlockEntityType` 등).
- content-registries 공개 API 진입점: `net.fabricmc.fabric.api.registry`(`CompostableRegistry`, `FuelValueEvents`, `StrippableBlockRegistry`, `TillableBlockRegistry`, `OxidizableBlocksRegistry` 등), `net.fabricmc.fabric.api.registry.fluid`, 진입점 `ContentRegistriesImpl`(`main`).
- menu 공개 API 진입점: `net.fabricmc.fabric.api.menu.v1`(`ExtendedMenuType`, `ExtendedMenuProvider`, `FabricMenuProvider`).
- NeoForge 연결 mixin: `RegistryManagerMixin`, `GameDataMixin`, `CommonHooksAccessor`, `BaseRegistryMixin`, `DataMapHooksMixin`, menu의 `ServerPlayerMixin`·`MenuProviderMixin`.
- 패키징 및 색인 진입점: `hostModuleResources` 작업, 색인 파일 `META-INF/plexusmodloader/host-modules.properties`.

## 빌드·테스트
```powershell
.\gradlew.bat :plexus-fabric-api-neoforge-v26_2:jar
.\gradlew.bat :plexus-bootstrap:neoForgeFabricApiGameTest
.\gradlew.bat :plexus-bootstrap:corpusFabricNeoForge
```
- `.\gradlew.bat :plexus-fabric-api-neoforge-v26_2:jar`: 호스트 모듈 jar를 빌드합니다.
- `.\gradlew.bat :plexus-bootstrap:neoForgeFabricApiGameTest`: `fabric_api_events` 테스트 모드가 블록 API 출력(`FabricBlockState.getAppearance(minecraft:stone) = minecraft:stone`, `FabricBlockState.getProvidedEnchantmentPower(minecraft:bookshelf) = 1.0`) 및 아이템 API 출력(`enchanting table offers minecraft:sharpness for minecraft:diamond_sword: false`, `enchanting table offers minecraft:sharpness for minecraft:iron_sword: true`, `default max stack size of minecraft:stick: 16`)을 출력하는지 확인합니다. 테스트 모드 `fabric-dynamic-registry`(동적 레지스트리 두 줄)와 `fabric-object-builder`(좀비 최대 체력 42.0, 화로 블록 엔티티가 훈연기를 받음, 엔티티 데이터 직렬화기 등록)의 줄도 확인합니다. 이는 Fabric 단독 실행인 `fabricApiGameTest` 및 `forgeFabricApiGameTest`와 동일한 출력입니다. 테스트 모드 `fabric-content-registries`(퇴비통, 연료 시간, 도끼 벗기기, 괭이 경작 네 줄)와 `fabric-oxidizable-blocks`(산화 단계 두 줄, 도끼 벗기기·벌집 밀랍칠·밀랍 벗기기 세 줄)는 `fabricApiGameTest`와 `neoForgeFabricApiGameTest`에서 같은 줄을 냅니다(MinecraftForge는 content-registries를 `224`로 빼므로 `forgeFabricApiGameTest`에 넣지 않음). 테스트 모드 `fabric-data-generation`은 Fabric API의 태그 제공자를 메모리 출력으로 실행해 생성된 파일의 경로, `replace`, 값, 제거 목록 네 줄을 냅니다(`forgeFabricApiGameTest`에서도 같음). 테스트 모드 `fabric-resource-conditions`(JSON 리로드 리스너와 레시피의 Fabric 조건 세 줄)는 `fabricApiGameTest`, `forgeFabricApiGameTest`, `neoForgeFabricApiGameTest`에서 같은 줄을 냅니다. 테스트 모드 `fabric-menu`(가짜 플레이어에게 확장 메뉴를 열고, 열기 데이터 요청 횟수를 직접 열 때와 `SimpleMenuProvider`로 감쌀 때 세는 세 줄)는 `fabricApiGameTest`, `forgeFabricApiGameTest`, `neoForgeFabricApiGameTest`에서 같은 줄을 냅니다(MinecraftForge 옆에서는 원본 menu 모듈이 실행되고, Forge 레지스트리가 건너뛰는 Fabric의 항목 추가 이벤트를 Plexus Mod Loader가 내보냄). 테스트 모드 `fabric-entity-events`(잠자기 이벤트 네 줄)도 세 작업에서 같은 줄을 냅니다(26.2 NeoForge 옆에서는 원본 모듈).
- `.\gradlew.bat :plexus-bootstrap:corpusFabricNeoForge`: 실제 모드 묶음의 Fabric 모드를 NeoForge 옆에서 실행하여 검증합니다.
- `plexus-runtime-impl`의 단위 테스트 `HostModulesTest`로 대체 로직을 검증합니다.

## 대표 흐름
1. `hostModuleResources` 작업이 jar를 `META-INF/plexusmodloader/host-modules/neoforge-26.2.0.93/`에 배치하고 `META-INF/plexusmodloader/host-modules.properties` 색인을 작성합니다. 소모성 구성 `hostModuleElements`가 이를 `plexus-bridge-fabric`에 전달하여 해당 jar에만 포함합니다.
2. 런타임(`plexus-runtime-impl`)에서 `NeoForge 26.2.0.93`이 호스트일 때, `HostPatchCheck`가 패치에 맞지 않는 원래 모듈을 먼저 `PLEXUS-COMPAT-224`로 표시합니다.
3. `HostModules.replaceLeftOut`이 `224`로 빠졌거나 `always`로 표시된 모듈마다 해당 호스트 릴리스용으로 색인된 호스트 모듈을 추출하고, 일반 모드 파일처럼 평가하여 원래 모듈 자리에 넣은 뒤 교체 내역을 로그에 남깁니다.
4. 교체된 결과에 대해 `HostPatchCheck`를 다시 실행하여 호스트 모듈 역시 패치에 맞는지 확인합니다.
5. 링크 검증(`ModLinkVerifier`) 단계에서 바닐라 jar보다 먼저 호스트의 패치된 게임 및 로더 jar를 읽어 링크를 검증합니다.

## 알려진 제한
- `NeoForge 26.2.0.93` 전용으로만 빌드되었습니다.
- 새로운 NeoForge 릴리스가 나오면 다시 빌드하고 검증해야 합니다.
- 창 작업 `neoForgeFabricClientProbe`(테스트 모드 `fabric-client-probe`)가 클라이언트에서 툴팁 줄, 화면 이벤트, 확장 메뉴 화면과 열기 데이터, 물 유체 처리기, 스트리밍 사운드의 `getAudioStream`을 Fabric 단독(`fabricClientProbe`)과 같은 줄로 확인합니다. 렌더링 결과(HUD 요소, 화면 속 화면, FRAPI 메시, Indigo의 조명, 모델 플러그인)는 눈으로 보지 않았고, 이를 확인하는 테스트 모드도 없습니다. object-builder의 클라이언트 쪽 순서(NeoForge 등록 뒤 Fabric 초기화)도 창 작업으로는 확인하지 않았습니다.
- NeoForge용 Sodium의 `sodium-frapi.mixins.json`은 FML이 Fabric 모드를 보지 못해 "required mod 'fabric_renderer_api_v1' is missing"으로 적용되지 않고, Indigo도 Sodium의 `contains_renderer` 속성을 보지 못해 자신을 등록합니다. Sodium과 함께 Fabric 모드의 FRAPI 블록 모델이 Fabric처럼 그려지는지는 확인하지 않았습니다.
- FFAPI는 NeoForge 26.2.0.88에 대해 만들어졌습니다. 26.2.0.93에서는 컴파일, 호스트 검사, 클래스 로딩까지만 확인합니다.
- 각 모듈의 버전보다 최신 버전의 모듈을 요구하는 Fabric 모드는 일반 버전 검사에서 처리됩니다.
- content-registries는 FFAPI처럼 Fabric 유체 동작(`FluidBehavior`)의 수영·익사·유체 점프·유체 안 이동·보트를 Fabric mixin으로 바꾸지 않고 NeoForge `FluidType`에 맡깁니다. Fabric 단독과 달리 Fabric이 등록한 유체 태그에서는 이 동작이 NeoForge의 유체 종류 설정을 따릅니다. 게임 테스트는 유체 동작을 확인하지 않습니다.
- data-generation은 게임 테스트에서 태그 제공자만 실행합니다. 레시피·전리품·모델·언어 제공자와 데이터 생성 실행(`-Dfabric-api.datagen`)은 NeoForge 옆에서 확인하지 않았습니다. NeoForge의 서버 `Main`은 Fabric API가 데이터 생성을 시작하는 `DedicatedServerSettings` 생성보다 먼저 EULA를 확인하므로, Plexus Mod Loader의 검증(EULA에 동의하지 않음)으로는 그 지점에 닿지 않습니다. Fabric 단독의 실제 데이터 생성은 `fabricDatagenRun`이 확인합니다. NeoForge 옆에서 생성된 태그 파일은 제거 목록을 NeoForge의 `remove`와 `fabric:remove`에 함께 씁니다(Fabric 단독은 `fabric:remove`만).
- menu는 게임 테스트에서 서버 쪽(메뉴가 열리고 열기 데이터를 요청함)을, 창 작업 `neoForgeFabricClientProbe`에서 클라이언트가 NeoForge의 열기 패킷으로 메뉴를 만들고(열기 데이터 42) `MenuScreens.register`로 등록한 화면을 여는 것을 확인합니다. Fabric 단독의 Fabric 열기 패킷(`fabric-menu-api-v1:open_screen`) 대신 NeoForge의 `AdvancedOpenScreenPayload`를 쓰므로, 클라이언트에도 NeoForge가 있어야 합니다.
