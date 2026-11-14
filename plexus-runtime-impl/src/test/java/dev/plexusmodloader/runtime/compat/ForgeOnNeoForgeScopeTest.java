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

    @Test
    void anotherForgeClassIsNamed() throws IOException {
        Path jar = jar("modlist.jar", Map.of(), null, code -> {
            code.ldc("x");
            code.invokestatic(
                    MOD_LIST, "isLoaded", MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String));
            code.pop();
        });
        assertEquals(List.of("net/minecraftforge/fml/ModList"), ForgeOnNeoForgeScope.unsupported(jar));
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

    @Test
    void classTransformingFilesAndMixinConfigsAreNamed() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("MixinConfigs", "example.mixins.json");
        Path jar = jar(
                "transforms.jar",
                Map.of(
                        "META-INF/accesstransformer.cfg",
                        "public net.minecraft.world.level.Level",
                        "META-INF/coremods.json",
                        "{}"),
                manifest,
                code -> {});
        assertEquals(
                List.of("META-INF/accesstransformer.cfg", "META-INF/coremods.json", "MixinConfigs"),
                ForgeOnNeoForgeScope.unsupported(jar));
    }

    @Test
    void splitKeepsTheOrderAndTheReasons() throws IOException {
        Path runs = jar("runs.jar", Map.of(), null, code -> {});
        Path left = jar("left.jar", Map.of(), null, code -> {
            code.ldc("x");
            code.invokestatic(
                    MOD_LIST, "isLoaded", MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String));
            code.pop();
        });
        ForgeOnNeoForgeScope.Split split = ForgeOnNeoForgeScope.split(List.of(left, runs), "26.2");
        assertEquals(List.of(runs), split.runnable());
        assertEquals(
                List.of(new ForgeOnNeoForgeScope.Left(left, List.of("net/minecraftforge/fml/ModList"))),
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
        ForgeOnNeoForgeScope.Split split = ForgeOnNeoForgeScope.split(List.of(runs, unlinked), "26.2")
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
        ForgeOnNeoForgeScope.Split split = ForgeOnNeoForgeScope.split(List.of(runs), "1.21.11");
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
