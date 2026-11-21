package dev.plexusmodloader.runtime.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.plexusmodloader.api.runtime.GameSide;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
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
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Forge on NeoForge: which Forge mods' access transformer lines NeoForge's FML applies as written. */
class ForgeOnNeoForgeAccessTransformersTest {

    private static final String GAME_CLASS = "net/minecraft/server/Bootstrap";

    @TempDir
    Path directory;

    /** As the corpus's access transformers: classes, fields, methods, constructors and wildcards the game has. */
    @Test
    void linesTheGameHasFit() throws IOException {
        Path jar = jar("""
                # a comment
                public net.minecraft.server.Bootstrap
                public-f net.minecraft.server.Bootstrap isBootstrapped # Z
                protected net.minecraft.server.Bootstrap wrapStreams()V
                public net.minecraft.server.Bootstrap <init>()V
                public net.minecraft.server.Bootstrap *
                public net.minecraft.server.Bootstrap *()

                """, null);
        assertEquals(List.of(), unfit(jar, GameSide.DEDICATED_SERVER));
    }

    /** A member only MinecraftForge's patches add, or a class the game does not have, would not apply. */
    @Test
    void missingClassesAndMembersAreNamed() throws IOException {
        Path jar = jar("""
                public net.minecraft.server.Bootstrap forgeOnly
                public net.minecraft.server.Bootstrap forgeOnly()V
                public net.minecraft.server.Bootstrap wrapStreams()I
                public net.minecraft.world.level.Missing
                public net.minecraftforge.common.ForgeHooks
                """, null);
        assertEquals(
                List.of(
                        "accesstransformer.cfg line 1: field forgeOnly is not in net.minecraft.server.Bootstrap",
                        "accesstransformer.cfg line 2: method forgeOnly()V is not in net.minecraft.server.Bootstrap",
                        "accesstransformer.cfg line 3: method wrapStreams()I is not in net.minecraft.server.Bootstrap",
                        "accesstransformer.cfg line 4: class net.minecraft.world.level.Missing is not in the game",
                        "accesstransformer.cfg line 5: class net.minecraftforge.common.ForgeHooks is in the loader"
                                + " libraries, which FML's access transformers do not transform"),
                unfit(jar, GameSide.DEDICATED_SERVER));
    }

    /** A dedicated server has no client classes; a client does, so there the class must be found. */
    @Test
    void clientClassesAreNotCheckedOnADedicatedServerOnly() throws IOException {
        Path jar = jar("public net.minecraft.client.Minecraft instance\n", null);
        assertEquals(List.of(), unfit(jar, GameSide.DEDICATED_SERVER));
        assertEquals(
                List.of("accesstransformer.cfg line 1: class net.minecraft.client.Minecraft is not in the game"),
                unfit(jar, GameSide.CLIENT));
    }

    /** Lines whose syntax NeoForge's engine is not known to read the same way are not trusted. */
    @Test
    void otherSyntaxIsNamed() throws IOException {
        Path jar = jar("""
                private net.minecraft.server.Bootstrap isBootstrapped
                public+f net.minecraft.server.Bootstrap isBootstrapped
                public
                public net.minecraft.server.Bootstrap isBootstrapped extra
                public net.minecraft.server.Bootstrap wrapStreams(V
                """, null);
        assertEquals(
                List.of(
                        "accesstransformer.cfg line 1: access 'private' is not checked (only public or protected,"
                                + " with or without -f)",
                        "accesstransformer.cfg line 2: access 'public+f' is not checked (only public or protected,"
                                + " with or without -f)",
                        "accesstransformer.cfg line 3: 'public' is not <access> <class> [<member>]",
                        "accesstransformer.cfg line 4: 'public net.minecraft.server.Bootstrap isBootstrapped extra'"
                                + " is not <access> <class> [<member>]",
                        "accesstransformer.cfg line 5: 'wrapStreams(V' has no valid descriptor"),
                unfit(jar, GameSide.DEDICATED_SERVER));
    }

    /** The mod's own classes are in FML's game layer too; access transformers named in mods.toml are not read. */
    @Test
    void theModsOwnClassesFitAndModsTomlEntriesAreNamed() throws IOException {
        Path jar = jar(
                "public example.Own secret\n",
                "modLoader = \"javafml\"\n[[accessTransformers]]\nfile = \"META-INF/other.cfg\"\n");
        assertEquals(List.of("[[accessTransformers]] in mods.toml is not checked"), unfit(jar, GameSide.CLIENT));
    }

    @Test
    void aJarWithoutAccessTransformerFits() throws IOException {
        assertEquals(List.of(), unfit(jar(null, null), GameSide.CLIENT));
    }

    private static List<String> unfit(Path jar, GameSide side) throws IOException {
        Map<String, byte[]> game = Map.of(GAME_CLASS, gameClass());
        Function<String, Optional<byte[]>> libraries =
                name -> name.startsWith("net/minecraftforge/") ? Optional.of(new byte[0]) : Optional.empty();
        return ForgeOnNeoForgeAccessTransformers.unfit(
                jar, side, name -> Optional.ofNullable(game.get(name)), libraries);
    }

    /** {@code Bootstrap} as javap shows it in NeoForge's patched game: a field, a method and a constructor. */
    private static byte[] gameClass() {
        return ClassFile.of().build(ClassDesc.ofInternalName(GAME_CLASS), type -> {
            type.withField("isBootstrapped", ConstantDescs.CD_boolean, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC);
            type.withMethodBody(
                    "wrapStreams",
                    MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC,
                    code -> code.return_());
            type.withMethodBody(
                    ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, code -> {
                        code.aload(0);
                        code.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                        code.return_();
                    });
        });
    }

    private Path jar(String accessTransformer, String modsToml) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(
                "example/Own.class",
                ClassFile.of()
                        .build(
                                ClassDesc.of("example.Own"),
                                type -> type.withField("secret", ConstantDescs.CD_int, ClassFile.ACC_PRIVATE)));
        if (accessTransformer != null) {
            entries.put(ForgeOnNeoForgeAccessTransformers.FILE, accessTransformer.getBytes(StandardCharsets.UTF_8));
        }
        if (modsToml != null) {
            entries.put("META-INF/mods.toml", modsToml.getBytes(StandardCharsets.UTF_8));
        }
        Path jar = Files.createTempFile(directory, "mod", ".jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream output = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }
}
