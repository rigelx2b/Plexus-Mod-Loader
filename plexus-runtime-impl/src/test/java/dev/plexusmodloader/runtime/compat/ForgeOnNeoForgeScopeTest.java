package dev.plexusmodloader.runtime.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Forge on NeoForge, F-N2 and F-N3: which Forge mods use only the Forge API NeoForge's host runs. */
class ForgeOnNeoForgeScopeTest {

    private static final ClassDesc CONTEXT =
            ClassDesc.ofInternalName("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext");
    private static final ClassDesc SETUP =
            ClassDesc.ofInternalName("net/minecraftforge/fml/event/lifecycle/FMLCommonSetupEvent");
    private static final ClassDesc BUS_GROUP = ClassDesc.ofInternalName("net/minecraftforge/eventbus/api/bus/BusGroup");
    private static final ClassDesc EVENT_BUS = ClassDesc.ofInternalName("net/minecraftforge/eventbus/api/bus/EventBus");
    private static final ClassDesc MOD_LIST = ClassDesc.ofInternalName("net/minecraftforge/fml/ModList");
    private static final ClassDesc MOD_CONTAINER = ClassDesc.ofInternalName("net/minecraftforge/fml/ModContainer");
    private static final ClassDesc MOD_LOADING_CONTEXT =
            ClassDesc.ofInternalName("net/minecraftforge/fml/ModLoadingContext");
    private static final ClassDesc MOD_INFO = ClassDesc.ofInternalName("net/minecraftforge/forgespi/language/IModInfo");
    private static final ClassDesc ARTIFACT_VERSION =
            ClassDesc.ofInternalName("org/apache/maven/artifact/versioning/ArtifactVersion");
    private static final ClassDesc OPTIONAL = ClassDesc.of("java.util.Optional");
    private static final ClassDesc DEFERRED_REGISTER =
            ClassDesc.ofInternalName("net/minecraftforge/registries/DeferredRegister");
    private static final ClassDesc REGISTRY_OBJECT =
            ClassDesc.ofInternalName("net/minecraftforge/registries/RegistryObject");
    private static final ClassDesc KEYS =
            ClassDesc.ofInternalName("net/minecraftforge/registries/ForgeRegistries$Keys");
    private static final ClassDesc RESOURCE_KEY = ClassDesc.ofInternalName("net/minecraft/resources/ResourceKey");
    private static final ClassDesc FORGE_REGISTRIES =
            ClassDesc.ofInternalName("net/minecraftforge/registries/ForgeRegistries");
    private static final ClassDesc FORGE_REGISTRY =
            ClassDesc.ofInternalName("net/minecraftforge/registries/IForgeRegistry");
    private static final ClassDesc IDENTIFIER = ClassDesc.ofInternalName("net/minecraft/resources/Identifier");
    private static final ClassDesc STRING = ConstantDescs.CD_String;
    private static final ClassDesc MINECRAFT_FORGE =
            ClassDesc.ofInternalName("net/minecraftforge/common/MinecraftForge");
    private static final ClassDesc MIGRATION_HELPER =
            ClassDesc.ofInternalName("net/minecraftforge/common/EventBusMigrationHelper");
    private static final ClassDesc FML_PATHS = ClassDesc.ofInternalName("net/minecraftforge/fml/loading/FMLPaths");
    private static final ClassDesc PATH = ClassDesc.of("java.nio.file.Path");
    private static final ClassDesc DIST = ClassDesc.ofInternalName("net/minecraftforge/api/distmarker/Dist");
    private static final ClassDesc FML_ENVIRONMENT =
            ClassDesc.ofInternalName("net/minecraftforge/fml/loading/FMLEnvironment");
    private static final ClassDesc FML_LOADER = ClassDesc.ofInternalName("net/minecraftforge/fml/loading/FMLLoader");
    private static final ClassDesc SUPPLIER = ClassDesc.of("java.util.function.Supplier");

    @TempDir
    Path directory;

    /** As test-mods/forge-on-neoforge-hello: the context's mod bus group and the setup event's bus. */
    @Test
    void aModUsingOnlyTheContextTheSetupEventAndTheEventBusRuns() throws IOException {
        Path jar = jar("hello.jar", Map.of(), null, code -> {
            code.aload(1);
            code.invokevirtual(CONTEXT, "getModBusGroup", MethodTypeDesc.of(BUS_GROUP));
            code.invokestatic(SETUP, "getBus", MethodTypeDesc.of(EVENT_BUS, BUS_GROUP));
            code.pop();
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /** As most corpus Forge mods: a display test registered on the mod's context, which Forge's container keeps. */
    @Test
    void aDisplayTestOnTheContextRuns() throws IOException {
        ClassDesc displayTest = ClassDesc.ofInternalName("net/minecraftforge/fml/IExtensionPoint$DisplayTest");
        ClassDesc supplier = ClassDesc.of("java.util.function.Supplier");
        Path jar = jar("display.jar", Map.of(), null, code -> {
            code.aload(1);
            code.ldc(displayTest);
            code.aconst_null();
            code.invokevirtual(
                    CONTEXT,
                    "registerExtensionPoint",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Class, supplier));
            code.aload(1);
            code.getstatic(displayTest, "IGNORE_ALL_VERSION", supplier);
            code.invokevirtual(CONTEXT, "registerDisplayTest", MethodTypeDesc.of(ConstantDescs.CD_void, supplier));
            code.new_(displayTest);
            code.dup();
            code.aconst_null();
            code.aconst_null();
            code.invokespecial(
                    displayTest,
                    ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, supplier, ClassDesc.of("java.util.function.BiPredicate")));
            code.pop();
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /** The context's other members need Forge's loading context state, or hand out Forge's mod container. */
    @Test
    void theContextsOtherMembersAreNamed() throws IOException {
        Path jar = jar("context.jar", Map.of(), null, code -> {
            code.invokestatic(CONTEXT, "get", MethodTypeDesc.of(CONTEXT));
            code.pop();
            code.aload(1);
            code.invokevirtual(CONTEXT, "getActiveNamespace", MethodTypeDesc.of(STRING));
            code.pop();
            code.aload(1);
            code.invokevirtual(CONTEXT, "getContainer", MethodTypeDesc.of(ConstantDescs.CD_Object));
            code.pop();
        });
        assertEquals(
                List.of(
                        "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.get",
                        "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.getActiveNamespace",
                        "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.getContainer"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    @Test
    void anotherForgeClassIsNamed() throws IOException {
        ClassDesc tracker = ClassDesc.ofInternalName("net/minecraftforge/fml/config/ConfigTracker");
        Path jar = jar("tracker.jar", Map.of(), null, code -> {
            code.ldc(tracker);
            code.pop();
        });
        assertEquals(List.of("net/minecraftforge/fml/config/ConfigTracker"), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * As test-mods/forge-on-neoforge-config (F-N6): a config screen factory registered as an extension point of the mod's
     * context and through {@code MinecraftForge.registerConfigScreen}, which NeoForge's mod list gets from the host.
     */
    @Test
    void aConfigScreenRuns() throws IOException {
        ClassDesc factory =
                ClassDesc.ofInternalName("net/minecraftforge/client/ConfigScreenHandler$ConfigScreenFactory");
        ClassDesc function = ClassDesc.of("java.util.function.Function");
        Path jar = jar("screen.jar", Map.of(), null, code -> {
            code.aload(1);
            code.ldc(factory);
            code.aconst_null();
            code.invokevirtual(
                    CONTEXT,
                    "registerExtensionPoint",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Class, SUPPLIER));
            code.new_(factory);
            code.dup();
            code.aconst_null();
            code.invokespecial(factory, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, function));
            code.pop();
            code.aconst_null();
            code.invokestatic(
                    MINECRAFT_FORGE, "registerConfigScreen", MethodTypeDesc.of(ConstantDescs.CD_void, function));
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * As test-mods/forge-on-neoforge-config (F-N6): a config spec built and registered on the mod's context and its
     * container, read in production, and its loading event; a server's configs sent to a client are not.
     */
    @Test
    void aModWithAForgeConfigRuns() throws IOException {
        ClassDesc spec = ClassDesc.ofInternalName("net/minecraftforge/common/ForgeConfigSpec");
        ClassDesc builder = ClassDesc.ofInternalName("net/minecraftforge/common/ForgeConfigSpec$Builder");
        ClassDesc intValue = ClassDesc.ofInternalName("net/minecraftforge/common/ForgeConfigSpec$IntValue");
        ClassDesc type = ClassDesc.ofInternalName("net/minecraftforge/fml/config/ModConfig$Type");
        ClassDesc modConfig = ClassDesc.ofInternalName("net/minecraftforge/fml/config/ModConfig");
        ClassDesc configSpec = ClassDesc.ofInternalName("net/minecraftforge/fml/config/IConfigSpec");
        ClassDesc loading = ClassDesc.ofInternalName("net/minecraftforge/fml/event/config/ModConfigEvent$Loading");
        Path jar = jar("config.jar", Map.of(), null, code -> {
            code.new_(builder);
            code.dup();
            code.invokespecial(builder, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void));
            code.dup();
            code.ldc("value");
            code.iconst_1();
            code.iconst_0();
            code.bipush(10);
            code.invokevirtual(
                    builder,
                    "defineInRange",
                    MethodTypeDesc.of(
                            intValue, STRING, ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
            code.invokevirtual(intValue, "get", MethodTypeDesc.of(ConstantDescs.CD_Object));
            code.pop();
            code.invokevirtual(builder, "build", MethodTypeDesc.of(spec));
            code.astore(2);
            code.aload(1);
            code.getstatic(type, "COMMON", type);
            code.aload(2);
            code.invokevirtual(CONTEXT, "registerConfig", MethodTypeDesc.of(ConstantDescs.CD_void, type, configSpec));
            code.aconst_null();
            code.invokestatic(loading, "getBus", MethodTypeDesc.of(EVENT_BUS, BUS_GROUP));
            code.pop();
            code.aconst_null();
            code.aconst_null();
            code.invokevirtual(MOD_CONTAINER, "addConfig", MethodTypeDesc.of(ConstantDescs.CD_void, modConfig));
            code.aconst_null();
            code.aconst_null();
            code.invokevirtual(
                    modConfig, "acceptSyncedConfig", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
        });
        assertEquals(
                List.of("net/minecraftforge/fml/config/ModConfig.acceptSyncedConfig"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * As most corpus Forge mods that read Forge's mod list (Xaero's maps, Ping Wheel, Carry On): whether a mod is loaded,
     * a mod's container and its ID, name and version, and the container the mod's code runs for, which the host sets up
     * for the Forge mods it runs.
     */
    @Test
    void readingForgesModListAndLoadingContextRuns() throws IOException {
        Path jar = jar("modlist.jar", Map.of(), null, code -> {
            code.ldc("x");
            code.invokestatic(MOD_LIST, "isLoaded", MethodTypeDesc.of(ConstantDescs.CD_boolean, STRING));
            code.pop();
            code.ldc("x");
            code.invokestatic(MOD_LIST, "getModContainerById", MethodTypeDesc.of(OPTIONAL, STRING));
            code.pop();
            code.invokestatic(MOD_LIST, "size", MethodTypeDesc.of(ConstantDescs.CD_int));
            code.pop();
            code.aconst_null();
            code.invokevirtual(MOD_CONTAINER, "getModInfo", MethodTypeDesc.of(MOD_INFO));
            code.dup();
            code.invokeinterface(MOD_INFO, "getVersion", MethodTypeDesc.of(ARTIFACT_VERSION));
            code.pop();
            code.dup();
            code.invokeinterface(MOD_INFO, "getDisplayName", MethodTypeDesc.of(STRING));
            code.pop();
            code.invokeinterface(MOD_INFO, "getModId", MethodTypeDesc.of(STRING));
            code.pop();
            code.invokestatic(MOD_LOADING_CONTEXT, "get", MethodTypeDesc.of(MOD_LOADING_CONTEXT));
            code.dup();
            code.invokevirtual(MOD_LOADING_CONTEXT, "getActiveNamespace", MethodTypeDesc.of(STRING));
            code.pop();
            code.invokevirtual(MOD_LOADING_CONTEXT, "getContainer", MethodTypeDesc.of(MOD_CONTAINER));
            code.invokevirtual(MOD_CONTAINER, "getModId", MethodTypeDesc.of(STRING));
            code.pop();
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * Forge's mod list here holds the Forge mods' containers only: what reads Forge's loading mod list (mod files, scan
     * data, sorted mod infos), a mod's file and dependencies, and config registration (F-N6) are named.
     */
    @Test
    void whatForgesModListDoesNotHoldIsNamed() throws IOException {
        ClassDesc modFileInfo = ClassDesc.ofInternalName("net/minecraftforge/forgespi/language/IModFileInfo");
        Path jar = jar("modfiles.jar", Map.of(), null, code -> {
            code.invokestatic(MOD_LIST, "getMods", MethodTypeDesc.of(ConstantDescs.CD_List));
            code.pop();
            code.ldc("x");
            code.invokestatic(MOD_LIST, "getModFileById", MethodTypeDesc.of(modFileInfo, STRING));
            code.pop();
            code.invokestatic(MOD_LIST, "getAllScanData", MethodTypeDesc.of(ConstantDescs.CD_List));
            code.pop();
            code.aconst_null();
            code.invokeinterface(MOD_INFO, "getOwningFile", MethodTypeDesc.of(modFileInfo));
            code.pop();
            code.aconst_null();
            code.invokeinterface(MOD_INFO, "getDependencies", MethodTypeDesc.of(ConstantDescs.CD_List));
            code.pop();
            code.invokestatic(MOD_LOADING_CONTEXT, "get", MethodTypeDesc.of(MOD_LOADING_CONTEXT));
            code.invokevirtual(MOD_LOADING_CONTEXT, "extension", MethodTypeDesc.of(ConstantDescs.CD_Object));
            code.pop();
        });
        assertEquals(
                List.of(
                        "net/minecraftforge/fml/ModList.getAllScanData",
                        "net/minecraftforge/fml/ModList.getModFileById",
                        "net/minecraftforge/fml/ModList.getMods",
                        "net/minecraftforge/fml/ModLoadingContext.extension",
                        "net/minecraftforge/forgespi/language/IModFileInfo",
                        "net/minecraftforge/forgespi/language/IModInfo.getDependencies",
                        "net/minecraftforge/forgespi/language/IModInfo.getOwningFile"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * {@code enqueueWork} on any lifecycle event, whichever class the call names: the host runs each stage's queue
     * through NeoForge's (F-N6).
     */
    @Test
    void enqueueWorkRunsOnEveryLifecycleEvent() throws IOException {
        ClassDesc clientSetup = ClassDesc.ofInternalName("net/minecraftforge/fml/event/lifecycle/FMLClientSetupEvent");
        MethodTypeDesc enqueue = MethodTypeDesc.of(
                ClassDesc.of("java.util.concurrent.CompletableFuture"), ClassDesc.of("java.lang.Runnable"));
        Path jar = jar("enqueue.jar", Map.of(), null, code -> {
            code.aconst_null();
            code.aconst_null();
            code.invokevirtual(SETUP, "enqueueWork", enqueue);
            code.pop();
            code.aconst_null();
            code.aconst_null();
            code.invokevirtual(clientSetup, "enqueueWork", enqueue);
            code.pop();
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /** As test-mods/forge-on-neoforge-events: a passed-on game event's own bus and its record accessors (F-N3). */
    @Test
    void aModListeningToAPassedOnGameEventRuns() throws IOException {
        ClassDesc tick = ClassDesc.ofInternalName("net/minecraftforge/event/TickEvent$LevelTickEvent$Pre");
        ClassDesc side = ClassDesc.ofInternalName("net/minecraftforge/fml/LogicalSide");
        Path jar = jar("events.jar", Map.of(), null, code -> {
            code.getstatic(tick, "BUS", EVENT_BUS);
            code.pop();
            code.aconst_null();
            code.checkcast(tick);
            code.invokevirtual(tick, "side", MethodTypeDesc.of(side));
            code.pop();
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * As test-mods/forge-on-neoforge-events (F-N6): the construct event's bus and the client player network events' buses
     * and getters.
     */
    @Test
    void theConstructEventAndTheClientPlayerNetworkEventsRun() throws IOException {
        ClassDesc construct = ClassDesc.ofInternalName("net/minecraftforge/fml/event/lifecycle/FMLConstructModEvent");
        ClassDesc network = ClassDesc.ofInternalName("net/minecraftforge/client/event/ClientPlayerNetworkEvent");
        ClassDesc loggingIn =
                ClassDesc.ofInternalName("net/minecraftforge/client/event/ClientPlayerNetworkEvent$LoggingIn");
        ClassDesc clone = ClassDesc.ofInternalName("net/minecraftforge/client/event/ClientPlayerNetworkEvent$Clone");
        ClassDesc localPlayer = ClassDesc.ofInternalName("net/minecraft/client/player/LocalPlayer");
        Path jar = jar("construct-and-network.jar", Map.of(), null, code -> {
            code.aconst_null();
            code.invokestatic(construct, "getBus", MethodTypeDesc.of(EVENT_BUS, BUS_GROUP));
            code.pop();
            code.getstatic(loggingIn, "BUS", EVENT_BUS);
            code.pop();
            code.aconst_null();
            code.invokeinterface(network, "getPlayer", MethodTypeDesc.of(localPlayer));
            code.pop();
            code.aconst_null();
            code.invokevirtual(clone, "getNewPlayer", MethodTypeDesc.of(localPlayer));
            code.pop();
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * As test-mods/forge-on-neoforge-content: a {@code DeferredRegister} made with a registry key (a vanilla one or one
     * of {@code ForgeRegistries.Keys}), its entries and its registration on the mod bus group (F-N4).
     */
    @Test
    void aModRegisteringWithARegistryKeyRuns() throws IOException {
        Path jar = jar("content.jar", Map.of(), null, code -> {
            code.getstatic(KEYS, "ITEMS", RESOURCE_KEY);
            code.ldc("example");
            code.invokestatic(DEFERRED_REGISTER, "create", MethodTypeDesc.of(DEFERRED_REGISTER, RESOURCE_KEY, STRING));
            code.dup();
            code.ldc("thing");
            code.aconst_null();
            code.invokevirtual(DEFERRED_REGISTER, "register", MethodTypeDesc.of(REGISTRY_OBJECT, STRING, SUPPLIER));
            code.invokevirtual(REGISTRY_OBJECT, "get", MethodTypeDesc.of(ConstantDescs.CD_Object));
            code.pop();
            code.aconst_null();
            code.invokevirtual(DEFERRED_REGISTER, "register", MethodTypeDesc.of(ConstantDescs.CD_void, BUS_GROUP));
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * As test-mods/forge-on-neoforge-registries (F-N4): a {@code DeferredRegister} made with {@code
     * ForgeRegistries.BLOCKS}, and lookups in {@code ForgeRegistries.ITEMS}, which the host makes views of the game's
     * registries.
     */
    @Test
    void forgeRegistryLookupsRun() throws IOException {
        Path jar = jar("forge-registry-lookups.jar", Map.of(), null, code -> {
            code.getstatic(FORGE_REGISTRIES, "BLOCKS", FORGE_REGISTRY);
            code.ldc("example");
            code.invokestatic(
                    DEFERRED_REGISTER, "create", MethodTypeDesc.of(DEFERRED_REGISTER, FORGE_REGISTRY, STRING));
            code.pop();
            code.getstatic(FORGE_REGISTRIES, "ITEMS", FORGE_REGISTRY);
            code.dup();
            code.aconst_null();
            code.invokeinterface(FORGE_REGISTRY, "getKey", MethodTypeDesc.of(IDENTIFIER, ConstantDescs.CD_Object));
            code.invokeinterface(FORGE_REGISTRY, "getValue", MethodTypeDesc.of(ConstantDescs.CD_Object, IDENTIFIER));
            code.pop();
            code.getstatic(FORGE_REGISTRIES, "ENTITY_TYPES", FORGE_REGISTRY);
            code.invokeinterface(FORGE_REGISTRY, "getValues", MethodTypeDesc.of(ClassDesc.of("java.util.Collection")));
            code.pop();
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * Of {@code ForgeRegistries}, the data pack registries ({@code BIOMES}, {@code PAINTING_VARIANTS}) and the fields of
     * Forge's own registries leave a mod out, as do the {@code IForgeRegistry} members the host's view does not answer, a
     * new registry and a key of a registry only Forge has.
     */
    @Test
    void forgeOnlyRegistriesAndUnansweredMembersAreNamed() throws IOException {
        ClassDesc registryHolder =
                ClassDesc.ofInternalName("net/minecraftforge/registries/DeferredRegister$RegistryHolder");
        Path jar = jar("forge-registries.jar", Map.of(), null, code -> {
            code.getstatic(FORGE_REGISTRIES, "BIOMES", FORGE_REGISTRY);
            code.dup();
            code.invokeinterface(FORGE_REGISTRY, "tags", MethodTypeDesc.of(ConstantDescs.CD_Object));
            code.pop();
            code.aconst_null();
            code.aconst_null();
            code.invokeinterface(
                    FORGE_REGISTRY,
                    "register",
                    MethodTypeDesc.of(ConstantDescs.CD_void, IDENTIFIER, ConstantDescs.CD_Object));
            code.getstatic(FORGE_REGISTRIES, "PAINTING_VARIANTS", FORGE_REGISTRY);
            code.pop();
            code.getstatic(FORGE_REGISTRIES, "FLUID_TYPES", registryHolder);
            code.pop();
            code.aconst_null();
            code.aconst_null();
            code.invokevirtual(DEFERRED_REGISTER, "makeRegistry", MethodTypeDesc.of(ConstantDescs.CD_Object, SUPPLIER));
            code.pop();
            code.getstatic(KEYS, "FLUID_TYPES", RESOURCE_KEY);
            code.pop();
        });
        assertEquals(
                List.of(
                        "net/minecraftforge/registries/DeferredRegister$RegistryHolder",
                        "net/minecraftforge/registries/DeferredRegister.makeRegistry",
                        "net/minecraftforge/registries/ForgeRegistries$Keys.FLUID_TYPES",
                        "net/minecraftforge/registries/ForgeRegistries.BIOMES",
                        "net/minecraftforge/registries/ForgeRegistries.FLUID_TYPES",
                        "net/minecraftforge/registries/ForgeRegistries.PAINTING_VARIANTS",
                        "net/minecraftforge/registries/IForgeRegistry.register",
                        "net/minecraftforge/registries/IForgeRegistry.tags"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * A mod's {@code @Mod.EventBusSubscriber} annotation and its bus constants run (F-N6), but asking a bus constant for
     * its group outside Forge's construction of the mod would reach {@code FMLJavaModLoadingContext.get()}.
     */
    @Test
    void anEventBusSubscribersBusGroupIsNamed() throws IOException {
        ClassDesc bus = ClassDesc.ofInternalName("net/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus");
        Path jar = jar("subscriber-bus.jar", Map.of(), null, code -> {
            code.getstatic(bus, "MOD", bus);
            code.invokevirtual(bus, "bus", MethodTypeDesc.of(SUPPLIER));
            code.pop();
        });
        assertEquals(
                List.of("net/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus.bus"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * The bus of {@code LevelEvent} would also get events that are not passed on, such as {@code Save}; every event of
     * {@code ServerLifecycleEvent} is passed on since F-N6 (about-to-start), so its bus runs.
     */
    @Test
    void theBusOfAnEventInterfaceWithEventsNotPassedOnIsNamed() throws IOException {
        ClassDesc level = ClassDesc.ofInternalName("net/minecraftforge/event/level/LevelEvent");
        ClassDesc load = ClassDesc.ofInternalName("net/minecraftforge/event/level/LevelEvent$Load");
        ClassDesc lifecycle = ClassDesc.ofInternalName("net/minecraftforge/event/server/ServerLifecycleEvent");
        Path jar = jar("level-bus.jar", Map.of(), null, code -> {
            code.getstatic(level, "BUS", EVENT_BUS);
            code.pop();
            code.getstatic(load, "BUS", EVENT_BUS);
            code.pop();
            code.getstatic(lifecycle, "BUS", EVENT_BUS);
            code.pop();
        });
        assertEquals(List.of("net/minecraftforge/event/level/LevelEvent.BUS"), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * Forge's reload listener event is passed on, but its condition context comes from a member only MinecraftForge's
     * patches add: adding a listener runs, reading the condition context is named.
     */
    @Test
    void theConditionContextOfTheReloadListenerEventIsNamed() throws IOException {
        ClassDesc event = ClassDesc.ofInternalName("net/minecraftforge/event/AddReloadListenerEvent");
        ClassDesc listener = ClassDesc.ofInternalName("net/minecraft/server/packs/resources/PreparableReloadListener");
        ClassDesc context =
                ClassDesc.ofInternalName("net/minecraftforge/common/crafting/conditions/ICondition$IContext");
        Path adds = jar("reload-listener.jar", Map.of(), null, code -> {
            code.aconst_null();
            code.aconst_null();
            code.invokevirtual(event, "addListener", MethodTypeDesc.of(ConstantDescs.CD_void, listener));
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(adds));
        Path reads = jar("reload-context.jar", Map.of(), null, code -> {
            code.aconst_null();
            code.invokevirtual(event, "getConditionContext", MethodTypeDesc.of(context));
            code.pop();
        });
        assertEquals(
                List.of(
                        "net/minecraftforge/common/crafting/conditions/ICondition$IContext",
                        "net/minecraftforge/event/AddReloadListenerEvent.getConditionContext"),
                ForgeOnNeoForgeScope.unsupported(reads));
    }

    /**
     * Access transformers and coremods are named; Mixin configurations are not, since FML applies them (the Mixin check
     * of the link check decides whether they fit).
     */
    @Test
    void classTransformingFilesAreNamed() throws IOException {
        Path jar = jar(
                "transforms.jar",
                Map.of(
                        "META-INF/accesstransformer.cfg",
                        "public net.minecraft.world.level.Level",
                        "META-INF/coremods.json",
                        "{}"),
                mixinManifest(),
                code -> {});
        // FML's access transformers apply the jar's access transformer; the link check checks its lines.
        assertEquals(List.of("META-INF/coremods.json"), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * With Fabric or Quilt mods in the game, NeoForge's mods share their Mixin (ADR-0026 30), which Forge mods' Mixin
     * configurations have not been run with: such a mod is left out naming them.
     */
    @Test
    void mixinConfigsAreNamedWhenTheMixinIsShared() throws IOException {
        Path mixins = jar("mixins.jar", Map.of(), mixinManifest(), code -> {});
        Path plain = jar("plain.jar", Map.of(), null, code -> {});
        assertEquals(
                List.of(mixins, plain),
                ForgeOnNeoForgeScope.split(List.of(mixins, plain), "26.2", false)
                        .runnable());
        ForgeOnNeoForgeScope.Split shared = ForgeOnNeoForgeScope.split(List.of(mixins, plain), "26.2", true);
        assertEquals(List.of(plain), shared.runnable());
        assertEquals(
                List.of(new ForgeOnNeoForgeScope.Left(mixins, List.of(ForgeOnNeoForgeScope.SHARED_MIXIN))),
                shared.leftOut());
    }

    /** As Chunky: Forge's own event bus ({@code MinecraftForge.EVENT_BUS}) and Forge's paths ({@code FMLPaths}). */
    @Test
    void forgesEventBusAndPathsRun() throws IOException {
        Path jar = jar("bus.jar", Map.of(), null, code -> {
            code.getstatic(MINECRAFT_FORGE, "EVENT_BUS", MIGRATION_HELPER);
            code.aload(0);
            code.invokevirtual(
                    MIGRATION_HELPER,
                    "register",
                    MethodTypeDesc.of(ClassDesc.of("java.util.Collection"), ConstantDescs.CD_Object));
            code.pop();
            code.getstatic(FML_PATHS, "CONFIGDIR", FML_PATHS);
            code.invokevirtual(FML_PATHS, "get", MethodTypeDesc.of(PATH));
            code.pop();
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /** Forge's startup and the setup of Forge's paths are the host's, not a mod's. */
    @Test
    void forgesStartupAndPathSetupAreNamed() throws IOException {
        Path jar = jar("setup.jar", Map.of(), null, code -> {
            code.invokestatic(MINECRAFT_FORGE, "initialize", MethodTypeDesc.of(ConstantDescs.CD_void));
            code.aconst_null();
            code.invokestatic(FML_PATHS, "loadAbsolutePaths", MethodTypeDesc.of(ConstantDescs.CD_void, PATH));
        });
        assertEquals(
                List.of(
                        "net/minecraftforge/common/MinecraftForge.initialize",
                        "net/minecraftforge/fml/loading/FMLPaths.loadAbsolutePaths"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    /** As test-mods/forge-on-neoforge-environment and EuphoriaPatcher: the side and game directory, which the host sets. */
    @Test
    void forgesSideAndGamePathRun() throws IOException {
        Path jar = jar("side.jar", Map.of(), null, code -> {
            code.getstatic(FML_ENVIRONMENT, "dist", DIST);
            code.getstatic(DIST, "DEDICATED_SERVER", DIST);
            code.pop2();
            code.invokestatic(FML_LOADER, "getDist", MethodTypeDesc.of(DIST));
            code.invokevirtual(DIST, "isClient", MethodTypeDesc.of(ConstantDescs.CD_boolean));
            code.pop();
            code.invokestatic(FML_LOADER, "getGamePath", MethodTypeDesc.of(PATH));
            code.pop();
            // F-N6: the host sets production as a player's Forge launch does.
            code.getstatic(FML_ENVIRONMENT, "production", ConstantDescs.CD_boolean);
            code.pop();
            code.invokestatic(FML_LOADER, "isProduction", MethodTypeDesc.of(ConstantDescs.CD_boolean));
            code.pop();
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /** The rest of Forge's launch state stays unset, so its members are named; so is Forge's {@code OnlyIn}. */
    @Test
    void forgesOtherLaunchStateAndOnlyInAreNamed() throws IOException {
        ClassDesc onlyIn = ClassDesc.ofInternalName("net/minecraftforge/api/distmarker/OnlyIn");
        Path jar = jar("launch.jar", Map.of(), null, code -> {
            code.invokestatic(FML_LOADER, "getLoadingModList", MethodTypeDesc.of(ConstantDescs.CD_Object));
            code.pop();
            code.ldc(onlyIn);
            code.pop();
        });
        assertEquals(
                List.of(
                        "net/minecraftforge/api/distmarker/OnlyIn",
                        "net/minecraftforge/fml/loading/FMLLoader.getLoadingModList"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    private static Manifest mixinManifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("MixinConfigs", "example.mixins.json");
        return manifest;
    }

    @Test
    void splitKeepsTheOrderAndTheReasons() throws IOException {
        Path runs = jar("runs.jar", Map.of(), null, code -> {});
        Path left = jar("left.jar", Map.of(), null, code -> {
            code.invokestatic(MOD_LIST, "getMods", MethodTypeDesc.of(ConstantDescs.CD_List));
            code.pop();
        });
        ForgeOnNeoForgeScope.Split split = ForgeOnNeoForgeScope.split(List.of(left, runs), "26.2", false);
        assertEquals(List.of(runs), split.runnable());
        assertEquals(
                List.of(new ForgeOnNeoForgeScope.Left(left, List.of("net/minecraftforge/fml/ModList.getMods"))),
                split.leftOut());
        ForgeOnNeoForgeScope.Split declined = split.without(List.of(runs), ForgeOnNeoForgeScope.DECLINED_BY_HOST);
        assertEquals(List.of(), declined.runnable());
        assertEquals(
                new ForgeOnNeoForgeScope.Left(runs, List.of(ForgeOnNeoForgeScope.DECLINED_BY_HOST)),
                declined.leftOut().getLast());
    }

    /** Jars the link check leaves out keep their own reasons; the others stay runnable. */
    @Test
    void splitWithoutKeepsEachJarsReasons() throws IOException {
        Path runs = jar("runs.jar", Map.of(), null, code -> {});
        Path unlinked = jar("unlinked.jar", Map.of(), null, code -> {});
        ForgeOnNeoForgeScope.Split split = ForgeOnNeoForgeScope.split(List.of(runs, unlinked), "26.2", false)
                .without(Map.of(unlinked, List.of("1 Minecraft method(s) or field(s) ...")));
        assertEquals(List.of(runs), split.runnable());
        assertEquals(
                List.of(new ForgeOnNeoForgeScope.Left(unlinked, List.of("1 Minecraft method(s) or field(s) ..."))),
                split.leftOut());
    }

    /** On a version F-N2 was not run on, every Forge mod is left out, naming the version. */
    @Test
    void onAnUnverifiedVersionEveryForgeModIsLeftOut() throws IOException {
        Path runs = jar("runs.jar", Map.of(), null, code -> {});
        ForgeOnNeoForgeScope.Split split = ForgeOnNeoForgeScope.split(List.of(runs), "1.21.11", false);
        assertEquals(List.of(), split.runnable());
        assertEquals(List.of(new ForgeOnNeoForgeScope.Left(runs, List.of("minecraft 1.21.11"))), split.leftOut());
    }

    /**
     * As test-mods/forge-on-neoforge-network (F-N6 networking, ADR-0051 22): a play-phase SimpleChannel built with
     * ChannelBuilder, its message builder, sends to PacketDistributor targets and the context of a received message.
     */
    @Test
    void aSimpleChannelOfThePlayPhaseRuns() throws IOException {
        ClassDesc builder = ClassDesc.ofInternalName("net/minecraftforge/network/ChannelBuilder");
        ClassDesc channel = ClassDesc.ofInternalName("net/minecraftforge/network/SimpleChannel");
        ClassDesc messages = ClassDesc.ofInternalName("net/minecraftforge/network/SimpleChannel$MessageBuilder");
        ClassDesc distributor = ClassDesc.ofInternalName("net/minecraftforge/network/PacketDistributor");
        ClassDesc target = ClassDesc.ofInternalName("net/minecraftforge/network/PacketDistributor$PacketTarget");
        ClassDesc context = ClassDesc.ofInternalName("net/minecraftforge/event/network/CustomPayloadEvent$Context");
        ClassDesc biConsumer = ClassDesc.of("java.util.function.BiConsumer");
        Path jar = jar("network.jar", Map.of(), null, code -> {
            code.ldc("example:main");
            code.invokestatic(builder, "named", MethodTypeDesc.of(builder, STRING));
            code.iconst_1();
            code.invokevirtual(builder, "networkProtocolVersion", MethodTypeDesc.of(builder, ConstantDescs.CD_int));
            code.invokevirtual(builder, "simpleChannel", MethodTypeDesc.of(channel));
            code.ldc(ConstantDescs.CD_String);
            code.invokevirtual(channel, "messageBuilder", MethodTypeDesc.of(messages, ClassDesc.of("java.lang.Class")));
            code.aconst_null();
            code.invokevirtual(messages, "consumerMainThread", MethodTypeDesc.of(messages, biConsumer));
            code.invokevirtual(messages, "add", MethodTypeDesc.of(channel));
            code.pop();
            code.aconst_null();
            code.ldc("hello");
            code.getstatic(distributor, "SERVER", distributor);
            code.invokevirtual(distributor, "noArg", MethodTypeDesc.of(target));
            code.invokevirtual(
                    channel, "send", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, target));
            code.aconst_null();
            code.invokevirtual(
                    context, "getSender", MethodTypeDesc.of(ClassDesc.of("net.minecraft.server.level.ServerPlayer")));
            code.pop();
            code.aconst_null();
            code.iconst_1();
            code.invokevirtual(
                    context, "setPacketHandled", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean));
            // A version test written as a lambda takes the status of the remote side.
            ClassDesc status = ClassDesc.ofInternalName("net/minecraftforge/network/Channel$VersionTest$Status");
            code.getstatic(status, "PRESENT", status);
            code.pop();
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /**
     * What the first networking slice does not carry is named: other phases' protocols and directions, event channels,
     * the remote check, the event bus of all payloads, and targets that are not PLAYER, SERVER or ALL.
     */
    @Test
    void networkingBeyondThePlayPhasesSimpleChannelIsNamed() throws IOException {
        ClassDesc builder = ClassDesc.ofInternalName("net/minecraftforge/network/ChannelBuilder");
        ClassDesc channel = ClassDesc.ofInternalName("net/minecraftforge/network/SimpleChannel");
        ClassDesc events = ClassDesc.ofInternalName("net/minecraftforge/network/EventNetworkChannel");
        ClassDesc protocol = ClassDesc.ofInternalName("net/minecraftforge/network/NetworkProtocol");
        ClassDesc distributor = ClassDesc.ofInternalName("net/minecraftforge/network/PacketDistributor");
        ClassDesc payloadEvent = ClassDesc.ofInternalName("net/minecraftforge/event/network/CustomPayloadEvent");
        Path jar = jar("network-beyond.jar", Map.of(), null, code -> {
            code.aconst_null();
            code.invokevirtual(builder, "eventNetworkChannel", MethodTypeDesc.of(events));
            code.pop();
            code.getstatic(protocol, "CONFIGURATION", protocol);
            code.pop();
            code.aconst_null();
            code.aconst_null();
            code.invokevirtual(
                    channel,
                    "isRemotePresent",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean, ClassDesc.of("net.minecraft.network.Connection")));
            code.pop();
            code.getstatic(payloadEvent, "BUS", EVENT_BUS);
            code.pop();
            code.getstatic(distributor, "TRACKING_ENTITY", distributor);
            code.pop();
        });
        assertEquals(
                List.of(
                        "net/minecraftforge/event/network/CustomPayloadEvent.BUS",
                        "net/minecraftforge/network/ChannelBuilder.eventNetworkChannel",
                        "net/minecraftforge/network/EventNetworkChannel",
                        "net/minecraftforge/network/NetworkProtocol",
                        "net/minecraftforge/network/PacketDistributor.TRACKING_ENTITY",
                        "net/minecraftforge/network/SimpleChannel.isRemotePresent"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    private interface Body {
        void emit(CodeBuilder code);
    }

    /** A jar with one class {@code example/Mod} whose method {@code run(FMLJavaModLoadingContext)} has {@code body}. */
    private Path jar(String name, Map<String, String> files, Manifest manifest, Body body) throws IOException {
        byte[] bytes = ClassFile.of()
                .build(
                        ClassDesc.of("example.Mod"),
                        type -> type.withMethodBody(
                                "run",
                                MethodTypeDesc.of(ConstantDescs.CD_void, CONTEXT),
                                ClassFile.ACC_PUBLIC,
                                code -> {
                                    body.emit(code);
                                    code.return_();
                                }));
        Path jar = directory.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream output =
                        manifest != null ? new JarOutputStream(out, manifest) : new JarOutputStream(out)) {
            output.putNextEntry(new JarEntry("example/Mod.class"));
            output.write(bytes);
            output.closeEntry();
            for (Map.Entry<String, String> file : files.entrySet()) {
                output.putNextEntry(new JarEntry(file.getKey()));
                output.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }
}
