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

    /**
     * The context's other members need Forge's loading context state or config tracking, or hand out Forge's mod
     * container; another extension point class (a config screen) is named too.
     */
    @Test
    void theContextsOtherMembersAndOtherExtensionPointsAreNamed() throws IOException {
        ClassDesc screenFactory =
                ClassDesc.ofInternalName("net/minecraftforge/client/ConfigScreenHandler$ConfigScreenFactory");
        Path jar = jar("context.jar", Map.of(), null, code -> {
            code.invokestatic(CONTEXT, "get", MethodTypeDesc.of(CONTEXT));
            code.pop();
            code.aload(1);
            code.invokevirtual(CONTEXT, "getActiveNamespace", MethodTypeDesc.of(STRING));
            code.pop();
            code.aload(1);
            code.invokevirtual(CONTEXT, "getContainer", MethodTypeDesc.of(ConstantDescs.CD_Object));
            code.pop();
            code.ldc(screenFactory);
            code.pop();
        });
        assertEquals(
                List.of(
                        "net/minecraftforge/client/ConfigScreenHandler$ConfigScreenFactory",
                        "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.get",
                        "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.getActiveNamespace",
                        "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.getContainer"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    @Test
    void anotherForgeClassIsNamed() throws IOException {
        ClassDesc config = ClassDesc.ofInternalName("net/minecraftforge/common/ForgeConfigSpec");
        Path jar = jar("config.jar", Map.of(), null, code -> {
            code.ldc(config);
            code.pop();
        });
        assertEquals(List.of("net/minecraftforge/common/ForgeConfigSpec"), ForgeOnNeoForgeScope.unsupported(jar));
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
            code.aconst_null();
            code.aconst_null();
            code.invokevirtual(
                    MOD_CONTAINER, "addConfig", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
            code.invokestatic(MOD_LOADING_CONTEXT, "get", MethodTypeDesc.of(MOD_LOADING_CONTEXT));
            code.invokevirtual(MOD_LOADING_CONTEXT, "extension", MethodTypeDesc.of(ConstantDescs.CD_Object));
            code.pop();
        });
        assertEquals(
                List.of(
                        "net/minecraftforge/fml/ModContainer.addConfig",
                        "net/minecraftforge/fml/ModList.getAllScanData",
                        "net/minecraftforge/fml/ModList.getModFileById",
                        "net/minecraftforge/fml/ModList.getMods",
                        "net/minecraftforge/fml/ModLoadingContext.extension",
                        "net/minecraftforge/forgespi/language/IModFileInfo",
                        "net/minecraftforge/forgespi/language/IModInfo.getDependencies",
                        "net/minecraftforge/forgespi/language/IModInfo.getOwningFile"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    /** {@code enqueueWork} needs Forge's deferred work queue, which runs in Forge's loading stages only. */
    @Test
    void aMemberThatNeedsForgesLoaderIsNamed() throws IOException {
        Path jar = jar("enqueue.jar", Map.of(), null, code -> {
            code.aload(1);
            code.aconst_null();
            code.invokevirtual(
                    SETUP,
                    "enqueueWork",
                    MethodTypeDesc.of(
                            ClassDesc.of("java.util.concurrent.CompletableFuture"),
                            ClassDesc.of("java.lang.Runnable")));
            code.pop();
        });
        assertEquals(
                List.of("net/minecraftforge/fml/event/lifecycle/FMLCommonSetupEvent.enqueueWork"),
                ForgeOnNeoForgeScope.unsupported(jar));
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
     * {@code ForgeRegistries.BLOCKS} is a Forge registry, which NeoForge's game does not have: a member of {@code
     * ForgeRegistries} or naming {@code IForgeRegistry} leaves the mod out, as do a new registry and a key of a registry
     * only Forge has.
     */
    @Test
    void forgeRegistriesNewRegistriesAndForgeOnlyKeysAreNamed() throws IOException {
        ClassDesc forgeRegistries = ClassDesc.ofInternalName("net/minecraftforge/registries/ForgeRegistries");
        ClassDesc forgeRegistry = ClassDesc.ofInternalName("net/minecraftforge/registries/IForgeRegistry");
        Path jar = jar("forge-registries.jar", Map.of(), null, code -> {
            code.getstatic(forgeRegistries, "BLOCKS", forgeRegistry);
            code.ldc("example");
            code.invokestatic(DEFERRED_REGISTER, "create", MethodTypeDesc.of(DEFERRED_REGISTER, forgeRegistry, STRING));
            code.aconst_null();
            code.invokevirtual(DEFERRED_REGISTER, "makeRegistry", MethodTypeDesc.of(ConstantDescs.CD_Object, SUPPLIER));
            code.pop();
            code.getstatic(KEYS, "FLUID_TYPES", RESOURCE_KEY);
            code.pop();
        });
        assertEquals(
                List.of(
                        "net/minecraftforge/registries/DeferredRegister.makeRegistry",
                        "net/minecraftforge/registries/ForgeRegistries$Keys.FLUID_TYPES",
                        "net/minecraftforge/registries/ForgeRegistries.BLOCKS",
                        "net/minecraftforge/registries/IForgeRegistry"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    /** Every lifecycle event queues {@code enqueueWork} work for Forge's stages, whichever class the call names. */
    @Test
    void enqueueWorkIsNamedOnEveryLifecycleEvent() throws IOException {
        ClassDesc clientSetup = ClassDesc.ofInternalName("net/minecraftforge/fml/event/lifecycle/FMLClientSetupEvent");
        Path jar = jar("client-enqueue.jar", Map.of(), null, code -> {
            code.aconst_null();
            code.aconst_null();
            code.invokevirtual(
                    clientSetup,
                    "enqueueWork",
                    MethodTypeDesc.of(
                            ClassDesc.of("java.util.concurrent.CompletableFuture"),
                            ClassDesc.of("java.lang.Runnable")));
            code.pop();
        });
        assertEquals(
                List.of("net/minecraftforge/fml/event/lifecycle/FMLClientSetupEvent.enqueueWork"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    /** The bus of {@code ServerLifecycleEvent} would also get events that are not passed on, such as about-to-start. */
    @Test
    void theBusOfAnEventInterfaceWithEventsNotPassedOnIsNamed() throws IOException {
        ClassDesc lifecycle = ClassDesc.ofInternalName("net/minecraftforge/event/server/ServerLifecycleEvent");
        Path jar = jar("lifecycle-bus.jar", Map.of(), null, code -> {
            code.getstatic(lifecycle, "BUS", EVENT_BUS);
            code.pop();
        });
        assertEquals(
                List.of("net/minecraftforge/event/server/ServerLifecycleEvent.BUS"),
                ForgeOnNeoForgeScope.unsupported(jar));
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
        });
        assertEquals(List.of(), ForgeOnNeoForgeScope.unsupported(jar));
    }

    /** The rest of Forge's launch state stays unset, so its members are named; so is Forge's {@code OnlyIn}. */
    @Test
    void forgesOtherLaunchStateAndOnlyInAreNamed() throws IOException {
        ClassDesc onlyIn = ClassDesc.ofInternalName("net/minecraftforge/api/distmarker/OnlyIn");
        Path jar = jar("launch.jar", Map.of(), null, code -> {
            code.getstatic(FML_ENVIRONMENT, "production", ConstantDescs.CD_boolean);
            code.pop();
            code.invokestatic(FML_LOADER, "isProduction", MethodTypeDesc.of(ConstantDescs.CD_boolean));
            code.pop();
            code.invokestatic(FML_LOADER, "getLoadingModList", MethodTypeDesc.of(ConstantDescs.CD_Object));
            code.pop();
            code.ldc(onlyIn);
            code.pop();
        });
        assertEquals(
                List.of(
                        "net/minecraftforge/api/distmarker/OnlyIn",
                        "net/minecraftforge/fml/loading/FMLEnvironment.production",
                        "net/minecraftforge/fml/loading/FMLLoader.getLoadingModList",
                        "net/minecraftforge/fml/loading/FMLLoader.isProduction"),
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
