package dev.plexusmodloader.runtime.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.plexusmodloader.api.runtime.GameSide;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Forge on NeoForge: whether a Forge mod's mixins name only what NeoForge's game has. */
class ForgeOnNeoForgeMixinsTest {

    private static final String SERVER = "net/minecraft/server/Server";
    private static final ClassDesc SERVER_DESC = ClassDesc.ofInternalName(SERVER);
    private static final ClassDesc CALLBACK_INFO =
            ClassDesc.ofInternalName("org/spongepowered/asm/mixin/injection/callback/CallbackInfo");
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";

    @TempDir
    Path directory;

    /** As Chunky's: an injection at a call in a method, an invoker, an accessor and a shadow, all in the game. */
    @Test
    void mixinsThatNameWhatTheGameHasFit() throws IOException {
        Path jar = modJar(
                config("\"mixins\": [\"ServerMixin\", \"ServerAccess\"]"),
                Map.of(
                        "example/mixin/ServerMixin",
                                injectMixin("tick", "Lnet/minecraft/server/Server;tickConnection()V"),
                        "example/mixin/ServerAccess", accessMixin("invokeSave", "getTicks")));
        assertEquals(List.of(), ForgeOnNeoForgeMixins.unfit(jar, GameSide.DEDICATED_SERVER, game()));
    }

    /** A target method only MinecraftForge's patch has, and a call only it makes, are named with the mixin. */
    @Test
    void aMethodOrCallTheGameLacksIsNamed() throws IOException {
        Path jar = modJar(
                config("\"mixins\": [\"ServerMixin\", \"OtherMixin\"]"),
                Map.of(
                        "example/mixin/ServerMixin",
                                injectMixin("forgeTick", "Lnet/minecraft/server/Server;tickConnection()V"),
                        "example/mixin/OtherMixin", injectMixin("tick", "Lnet/minecraft/server/Server;forgeHook()V")));
        assertEquals(
                List.of(
                        "example.mixins.json ServerMixin: @Inject method forgeTick is not in net.minecraft.server.Server",
                        "example.mixins.json OtherMixin: @Inject at INVOKE Lnet/minecraft/server/Server;forgeHook()V is"
                                + " not in net.minecraft.server.Server#tick"),
                ForgeOnNeoForgeMixins.unfit(jar, GameSide.DEDICATED_SERVER, game()));
    }

    @Test
    void aTargetClassTheGameLacksIsNamed() throws IOException {
        Path jar = modJar(
                config("\"mixins\": [\"ServerMixin\"]"),
                Map.of("example/mixin/ServerMixin", mixin("net/minecraftforge/Patched", type -> {})));
        assertEquals(
                List.of("example.mixins.json ServerMixin: target net.minecraftforge.Patched is not in the game"),
                ForgeOnNeoForgeMixins.unfit(jar, GameSide.DEDICATED_SERVER, game()));
    }

    /** An accessor, invoker or shadow of a member the game lacks is named. */
    @Test
    void anAccessedMemberTheGameLacksIsNamed() throws IOException {
        Path jar = modJar(
                config("\"mixins\": [\"ServerAccess\"]"),
                Map.of("example/mixin/ServerAccess", accessMixin("invokeForgeSave", "getForgeTicks")));
        assertEquals(
                List.of(
                        "example.mixins.json ServerAccess: @Invoker forgeSave()V is not in net.minecraft.server.Server",
                        "example.mixins.json ServerAccess: @Accessor forgeTicks (I) is not in"
                                + " net.minecraft.server.Server"),
                ForgeOnNeoForgeMixins.unfit(jar, GameSide.DEDICATED_SERVER, game()));
    }

    /** Client mixins are checked on the client only; the dedicated server never applies them. */
    @Test
    void theOtherSidesMixinsAreNotChecked() throws IOException {
        Path jar = modJar(
                config("\"client\": [\"ServerMixin\"]"),
                Map.of(
                        "example/mixin/ServerMixin",
                        injectMixin("forgeTick", "Lnet/minecraft/server/Server;tickConnection()V")));
        assertEquals(List.of(), ForgeOnNeoForgeMixins.unfit(jar, GameSide.DEDICATED_SERVER, game()));
        assertEquals(
                1, ForgeOnNeoForgeMixins.unfit(jar, GameSide.CLIENT, game()).size());
    }

    /** What the check does not read leaves the mod out: a plugin, a reference map, MixinExtras. */
    @Test
    void whatTheCheckDoesNotReadIsNamed() throws IOException {
        Path jar = modJar(
                config("\"plugin\": \"example.Plugin\", \"refmap\": \"example.refmap.json\", \"mixins\": [\"Extras\"]"),
                Map.of(
                        "example.refmap.json",
                        "{\"mappings\": {}}".getBytes(StandardCharsets.UTF_8),
                        "example/mixin/Extras",
                        mixin(
                                SERVER,
                                type -> type.withMethod(
                                        "wrap",
                                        MethodTypeDesc.of(ConstantDescs.CD_void),
                                        ClassFile.ACC_PRIVATE,
                                        method -> method.with(
                                                        RuntimeInvisibleAnnotationsAttribute.of(
                                                                Annotation.of(
                                                                        ClassDesc.ofDescriptor(
                                                                                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"))))
                                                .withCode(code -> code.return_())))));
        assertEquals(
                List.of(
                        "example.mixins.json: plugin example.Plugin is not checked",
                        "example.mixins.json: refmap example.refmap.json is not checked",
                        "example.mixins.json Extras: @WrapOperation (MixinExtras) is not checked"),
                ForgeOnNeoForgeMixins.unfit(jar, GameSide.DEDICATED_SERVER, game()));
    }

    /**
     * A reference map the jar lacks is none (Mixin uses the names as written, as 26.x Forge builds name one they do not
     * ship), and a mixin of the mod's own class is checked against the mod's jar.
     */
    @Test
    void anAbsentRefmapAndAMixinOfTheModsOwnClassFit() throws IOException {
        Path jar = modJar(
                config("\"refmap\": \"absent.refmap.json\", \"mixins\": [\"OwnMixin\"]"),
                Map.of(
                        "example/mixin/OwnMixin",
                        mixin("example/Own", type -> {}),
                        "example/Own",
                        ClassFile.of().build(ClassDesc.of("example.Own"), type -> {})));
        assertEquals(List.of(), ForgeOnNeoForgeMixins.unfit(jar, GameSide.DEDICATED_SERVER, game()));
    }

    /**
     * A class of the loader libraries (Forge's API jars) is not a target: FML's Mixin, in the game layer, never transforms
     * it, so the mixin would not apply.
     */
    @Test
    void aTargetInTheLoaderLibrariesIsNamed() throws IOException {
        String library = "net/minecraftforge/data/loading/DatagenModLoader";
        byte[] libraryClass = ClassFile.of().build(ClassDesc.ofInternalName(library), type -> {});
        Path jar = modJar(
                config("\"mixins\": [\"LibraryMixin\"]"),
                Map.of("example/mixin/LibraryMixin", mixin(library, type -> {})));
        assertEquals(
                List.of(
                        "example.mixins.json LibraryMixin: target net.minecraftforge.data.loading.DatagenModLoader is in"
                                + " the loader libraries, which FML's Mixin does not transform"),
                ForgeOnNeoForgeMixins.unfit(
                        jar,
                        GameSide.DEDICATED_SERVER,
                        game(),
                        name -> name.equals(library) ? Optional.of(libraryClass) : Optional.empty()));
    }

    /** The local and constant selectors of {@code @ModifyVariable} and {@code @ModifyConstant} are not read. */
    @Test
    void modifyVariableAndModifyConstantAreNamed() throws IOException {
        Path jar = modJar(config("\"mixins\": [\"Modify\"]"), Map.of("example/mixin/Modify", mixin(SERVER, type -> {
            for (String injector : List.of("ModifyVariable", "ModifyConstant")) {
                type.withMethod(
                        "modify" + injector,
                        MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int),
                        ClassFile.ACC_PRIVATE,
                        method -> method.with(RuntimeInvisibleAnnotationsAttribute.of(Annotation.of(
                                        ClassDesc.ofDescriptor(
                                                "Lorg/spongepowered/asm/mixin/injection/" + injector + ";"),
                                        AnnotationElement.of(
                                                "method", AnnotationValue.ofArray(AnnotationValue.ofString("tick"))))))
                                .withCode(code -> {
                                    code.iload(1);
                                    code.ireturn();
                                }));
            }
        })));
        assertEquals(
                List.of(
                        "example.mixins.json Modify: @ModifyVariable is not checked",
                        "example.mixins.json Modify: @ModifyConstant is not checked"),
                ForgeOnNeoForgeMixins.unfit(jar, GameSide.DEDICATED_SERVER, game()));
    }

    @Test
    void aMissingConfigurationIsNamed() throws IOException {
        Path jar = modJar(null, Map.of());
        assertEquals(
                List.of("example.mixins.json is not in the jar"),
                ForgeOnNeoForgeMixins.unfit(jar, GameSide.DEDICATED_SERVER, game()));
    }

    @Test
    void aJarWithoutMixinConfigsFits() throws IOException {
        Path jar = directory.resolve("plain.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("example/Mod.class"));
            output.write(ClassFile.of().build(ClassDesc.of("example.Mod"), type -> {}));
            output.closeEntry();
        }
        assertEquals(List.of(), ForgeOnNeoForgeMixins.unfit(jar, GameSide.DEDICATED_SERVER, game()));
    }

    private static String config(String lists) {
        return "{\"required\": true, \"package\": \"example.mixin\", " + lists + "}";
    }

    /**
     * The game: {@code Server} with {@code tick()V} calling {@code tickConnection()V}, {@code save()V} and an int field
     * {@code ticks}.
     */
    private static java.util.function.Function<String, Optional<byte[]>> game() {
        byte[] server = ClassFile.of().build(SERVER_DESC, type -> {
            type.withField("ticks", ConstantDescs.CD_int, ClassFile.ACC_PRIVATE);
            type.withMethodBody("tick", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, code -> {
                code.aload(0);
                code.invokevirtual(SERVER_DESC, "tickConnection", MethodTypeDesc.of(ConstantDescs.CD_void));
                code.return_();
            });
            type.withMethodBody(
                    "tickConnection",
                    MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC,
                    code -> code.return_());
            type.withMethodBody(
                    "save", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PRIVATE, code -> code.return_());
        });
        return name -> name.equals(SERVER) ? Optional.of(server) : Optional.empty();
    }

    /** A mixin of {@code target} with an {@code @Inject} into {@code method} at an {@code INVOKE} of {@code call}. */
    private static byte[] injectMixin(String method, String call) {
        return mixin(
                SERVER,
                type -> type.withMethod(
                        "onTick",
                        MethodTypeDesc.of(ConstantDescs.CD_void, CALLBACK_INFO),
                        ClassFile.ACC_PRIVATE,
                        builder -> builder.with(RuntimeInvisibleAnnotationsAttribute.of(Annotation.of(
                                        ClassDesc.ofDescriptor(INJECT),
                                        AnnotationElement.of(
                                                "method", AnnotationValue.ofArray(AnnotationValue.ofString(method))),
                                        AnnotationElement.of(
                                                "at",
                                                AnnotationValue.ofArray(AnnotationValue.ofAnnotation(Annotation.of(
                                                        ClassDesc.ofDescriptor(AT),
                                                        AnnotationElement.ofString("value", "INVOKE"),
                                                        AnnotationElement.ofString("target", call))))))))
                                .withCode(code -> code.return_())));
    }

    /**
     * An interface mixin of {@code Server} with an invoker {@code invoker()V}, an accessor {@code accessor()I} and a
     * shadow {@code tick()V}.
     */
    private static byte[] accessMixin(String invoker, String accessor) {
        return ClassFile.of().build(ClassDesc.of("example.mixin.Access"), type -> {
            type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
            type.with(mixinAnnotation(SERVER));
            type.withMethod(
                    invoker,
                    MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                    method -> method.with(
                            RuntimeInvisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.ofDescriptor(INVOKER)))));
            type.withMethod(
                    accessor,
                    MethodTypeDesc.of(ConstantDescs.CD_int),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                    method -> method.with(
                            RuntimeInvisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.ofDescriptor(ACCESSOR)))));
            type.withMethod(
                    "tick",
                    MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                    method -> method.with(
                            RuntimeInvisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.ofDescriptor(SHADOW)))));
        });
    }

    private static byte[] mixin(String target, Consumer<ClassBuilder> members) {
        return ClassFile.of().build(ClassDesc.of("example.mixin.Mixin"), type -> {
            type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT);
            type.with(mixinAnnotation(target));
            members.accept(type);
        });
    }

    private static RuntimeInvisibleAnnotationsAttribute mixinAnnotation(String target) {
        return RuntimeInvisibleAnnotationsAttribute.of(Annotation.of(
                ClassDesc.ofDescriptor(MIXIN),
                AnnotationElement.of(
                        "value", AnnotationValue.ofArray(AnnotationValue.ofClass(ClassDesc.ofInternalName(target))))));
    }

    /** A mod jar whose manifest names {@code example.mixins.json} ({@code config}, absent when null) with classes. */
    private Path modJar(String config, Map<String, byte[]> classes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("MixinConfigs", "example.mixins.json");
        Path jar = directory.resolve("mod.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        if (config != null) {
            entries.put("example.mixins.json", config.getBytes(StandardCharsets.UTF_8));
        }
        classes.forEach((name, bytes) -> entries.put(name.endsWith(".json") ? name : name + ".class", bytes));
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream output = new JarOutputStream(out, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }
}
