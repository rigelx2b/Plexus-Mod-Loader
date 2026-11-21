package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Forge on NeoForge: what the host sets up of Forge's {@code ModList} and {@code ModLoadingContext}, checked before launch. */
class ForgeModListCheckTest {

    private static final ClassDesc MOD_LIST = ClassDesc.ofInternalName(ForgeModListCheck.MOD_LIST);
    private static final ClassDesc CONTEXT = ClassDesc.ofInternalName(ForgeModListCheck.MOD_LOADING_CONTEXT);
    private static final ClassDesc CONTAINER = ClassDesc.ofInternalName("net/minecraftforge/fml/ModContainer");
    private static final ClassDesc IMPL = ClassDesc.ofInternalName(ForgeModListCheck.LOADING_MOD_LIST_IMPL);
    private static final ClassDesc STATE = ClassDesc.ofInternalName(ForgeModListCheck.SORTER_STATE);
    private static final ClassDesc MAP = ClassDesc.of("java.util.Map");

    @TempDir
    Path directory;

    /** Forge's own jars of the releases pinned for Minecraft 26.2 and 26.3, named by the build. */
    @ParameterizedTest
    @ValueSource(strings = {"plexus.forgeEnvironment262", "plexus.forgeEnvironment263"})
    void forgesPinnedJarsHaveWhatTheHostSetsUp(String property) throws IOException {
        String jars = System.getProperty(property);
        assertNotNull(jars, "the build names Forge's fmlcore and fmlloader jars in " + property);
        List<Path> paths =
                Arrays.stream(jars.split(File.pathSeparator)).map(Path::of).toList();
        assertEquals(List.of(), ForgeModListCheck.missing(only(paths, "fmlcore-"), only(paths, "fmlloader-")));
    }

    @Test
    void theShapeTheHostSetsUpPasses() throws IOException {
        assertEquals(List.of(), ForgeModListCheck.missing(coreJar(true, true), loaderJar(true)));
    }

    /** A member Forge mods may use that reads Forge's loading mod list would answer from a list the host leaves empty. */
    @Test
    void aMemberReadingWhatTheHostDoesNotSetIsNamed() throws IOException {
        assertEquals(
                List.of(
                        ForgeModListCheck.MOD_LIST + ": isLoaded() reads static field FILE_BY_ID, which is not set",
                        ForgeModListCheck.MOD_LIST
                                + ": isLoaded() calls net/minecraftforge/fml/loading/LoadingModList.getMods, which is"
                                + " not set up"),
                ForgeModListCheck.missing(coreJar(false, true), loaderJar(true)));
    }

    @Test
    void aContextWithoutItsActiveContainerSetterIsNamed() throws IOException {
        assertEquals(
                List.of(ForgeModListCheck.MOD_LOADING_CONTEXT
                        + ": missing instance method setActiveContainer(Lnet/minecraftforge/fml/ModContainer;)V"),
                ForgeModListCheck.missing(coreJar(true, false), loaderJar(true)));
    }

    @Test
    void aLoadingModListWithoutItsStateFieldIsNamed() throws IOException {
        assertEquals(
                List.of(ForgeModListCheck.LOADING_MOD_LIST_IMPL + ": missing private static non-final field temp L"
                        + ForgeModListCheck.SORTER_STATE + ";"),
                ForgeModListCheck.missing(coreJar(true, true), loaderJar(false)));
    }

    @Test
    void missingClassesAreNamed() throws IOException {
        Path empty = jar("empty.jar", Map.of());
        assertEquals(
                List.of(
                        "missing class " + ForgeModListCheck.MOD_LIST,
                        "missing class " + ForgeModListCheck.MOD_LOADING_CONTEXT,
                        "missing class " + ForgeModListCheck.LOADING_MOD_LIST_IMPL,
                        "missing class " + ForgeModListCheck.SORTER_STATE),
                ForgeModListCheck.missing(empty, empty));
    }

    private static Path only(List<Path> paths, String prefix) {
        List<Path> found = paths.stream()
                .filter(path -> path.getFileName().toString().startsWith(prefix))
                .toList();
        assertEquals(1, found.size(), () -> prefix + " in " + paths);
        return found.getFirst();
    }

    /**
     * Forge's {@code ModList} and {@code ModLoadingContext} as javap shows them for Forge 26.2-65.1.5 (the members the
     * check reads), or with {@code isLoaded} reading Forge's loading mod list, or without {@code setActiveContainer}.
     */
    private Path coreJar(boolean answersFromLoadedMods, boolean activeContainerSetter) throws IOException {
        int publicStatic = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC;
        byte[] modList = ClassFile.of().build(MOD_LIST, type -> {
            type.withField("indexedMods", MAP, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC);
            type.withMethodBody(
                    "setLoadedMods",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_List),
                    ClassFile.ACC_STATIC,
                    code -> code.return_());
            for (String name : ForgeModListCheck.ANSWERED) {
                type.withMethodBody(name, MethodTypeDesc.of(ConstantDescs.CD_Object), publicStatic, code -> {
                    if (name.equals("isLoaded") && !answersFromLoadedMods) {
                        code.getstatic(MOD_LIST, "FILE_BY_ID", MAP);
                        code.pop();
                        code.invokestatic(
                                ClassDesc.ofInternalName("net/minecraftforge/fml/loading/LoadingModList"),
                                "getMods",
                                MethodTypeDesc.of(ConstantDescs.CD_List),
                                true);
                        code.pop();
                    }
                    code.getstatic(MOD_LIST, "indexedMods", MAP);
                    code.areturn();
                });
            }
        });
        byte[] context = ClassFile.of().build(CONTEXT, type -> {
            type.withMethodBody(
                    "get",
                    MethodTypeDesc.of(CONTEXT),
                    publicStatic,
                    code -> code.aconst_null().areturn());
            if (activeContainerSetter) {
                type.withMethodBody(
                        "setActiveContainer",
                        MethodTypeDesc.of(ConstantDescs.CD_void, CONTAINER),
                        0,
                        code -> code.return_());
            }
        });
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(ForgeModListCheck.MOD_LIST + ".class", modList);
        entries.put(ForgeModListCheck.MOD_LOADING_CONTEXT + ".class", context);
        return jar("fmlcore-" + answersFromLoadedMods + activeContainerSetter + ".jar", entries);
    }

    /** Forge's {@code LoadingModListImpl} and {@code ModSorter.State}, or the former without its {@code temp} field. */
    private Path loaderJar(boolean stateField) throws IOException {
        byte[] impl = ClassFile.of().build(IMPL, type -> {
            if (stateField) {
                type.withField("temp", STATE, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC);
            }
        });
        byte[] state = ClassFile.of()
                .build(
                        STATE,
                        type -> type.withMethodBody(
                                ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_List, ConstantDescs.CD_List),
                                0,
                                code -> code.aload(0)
                                        .invokespecial(
                                                ConstantDescs.CD_Object,
                                                ConstantDescs.INIT_NAME,
                                                ConstantDescs.MTD_void)
                                        .return_()));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(ForgeModListCheck.LOADING_MOD_LIST_IMPL + ".class", impl);
        entries.put(ForgeModListCheck.SORTER_STATE + ".class", state);
        return jar("fmlloader-" + stateField + ".jar", entries);
    }

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path jar = directory.resolve(name);
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
