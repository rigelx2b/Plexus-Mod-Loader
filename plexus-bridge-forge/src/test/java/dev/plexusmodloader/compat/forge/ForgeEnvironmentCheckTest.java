package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Forge on NeoForge: what the host sets of Forge's launch state, checked before launch. */
class ForgeEnvironmentCheckTest {

    private static final ClassDesc DIST = ClassDesc.ofInternalName("net/minecraftforge/api/distmarker/Dist");
    private static final ClassDesc LOADER = ClassDesc.ofInternalName(ForgeEnvironmentCheck.FML_LOADER);
    private static final ClassDesc ENVIRONMENT =
            ClassDesc.ofInternalName("net/minecraftforge/fml/loading/FMLEnvironment");
    private static final ClassDesc PATH = ClassDesc.of("java.nio.file.Path");

    @TempDir
    Path directory;

    /** NeoForge's side, by name, is one of Forge's: the host maps one to the other with {@code Dist.valueOf}. */
    @Test
    void neoForgesAndForgesSidesHaveTheSameNames() {
        List<String> neoForge = Arrays.stream(net.neoforged.api.distmarker.Dist.values())
                .map(Enum::name)
                .toList();
        List<String> forge = Arrays.stream(net.minecraftforge.api.distmarker.Dist.values())
                .map(Enum::name)
                .toList();
        assertEquals(ForgeEnvironmentCheck.SIDES, neoForge);
        assertEquals(ForgeEnvironmentCheck.SIDES, forge);
    }

    /** Forge's own jars of the releases pinned for Minecraft 26.2 and 26.3, named by the build. */
    @ParameterizedTest
    @ValueSource(strings = {"plexus.forgeEnvironment262", "plexus.forgeEnvironment263"})
    void forgesPinnedJarsHaveWhatTheHostSets(String property) throws IOException {
        String jars = System.getProperty(property);
        assertNotNull(jars, "the build names Forge's fmlloader and mergetool-api jars in " + property);
        List<Path> paths =
                Arrays.stream(jars.split(File.pathSeparator)).map(Path::of).toList();
        Path fmlloader = only(paths, "fmlloader-");
        Path mergetoolApi = only(paths, "mergetool-api-");
        assertEquals(List.of(), ForgeEnvironmentCheck.missing(fmlloader, mergetoolApi));
    }

    @Test
    void theShapeTheHostSetsPasses() throws IOException {
        assertEquals(List.of(), ForgeEnvironmentCheck.missing(loaderJar(true, true), distJar()));
    }

    /** A getter that does more than return the field (another release) would not show what the host set. */
    @Test
    void aGetterThatComputesItsValueIsNamed() throws IOException {
        assertEquals(
                List.of(ForgeEnvironmentCheck.FML_LOADER + ": getGamePath() does not only return gamePath"),
                ForgeEnvironmentCheck.missing(loaderJar(false, true), distJar()));
    }

    /** An {@code FMLEnvironment} that does not copy {@code FMLLoader.getDist()} would not show the side the host set. */
    @Test
    void anEnvironmentThatDoesNotCopyTheSideIsNamed() throws IOException {
        assertEquals(
                List.of("net/minecraftforge/fml/loading/FMLEnvironment: <clinit> does not set dist from"
                        + " FMLLoader.getDist()"),
                ForgeEnvironmentCheck.missing(loaderJar(true, false), distJar()));
    }

    @Test
    void missingClassesAreNamed() throws IOException {
        Path empty = jar("empty.jar", Map.of());
        assertEquals(
                List.of(
                        "missing class net/minecraftforge/api/distmarker/Dist",
                        "missing class " + ForgeEnvironmentCheck.FML_LOADER,
                        "missing class net/minecraftforge/fml/loading/FMLEnvironment"),
                ForgeEnvironmentCheck.missing(empty, empty));
    }

    /** A getter the host's handles cannot call as Forge mods do (not public static) is named as missing. */
    @Test
    void aGetterThatIsNotPublicStaticIsNamed() throws IOException {
        assertEquals(
                List.of(ForgeEnvironmentCheck.FML_LOADER
                        + ": missing public static method getDist()Lnet/minecraftforge/api/distmarker/Dist;"),
                ForgeEnvironmentCheck.missing(loaderJar(true, true, false, true), distJar()));
    }

    /** An {@code FMLEnvironment} without its public static {@code dist} field is named apart from its {@code <clinit>}. */
    @Test
    void anEnvironmentWithoutItsDistFieldIsNamed() throws IOException {
        assertEquals(
                List.of("net/minecraftforge/fml/loading/FMLEnvironment: missing public static field dist"
                        + " Lnet/minecraftforge/api/distmarker/Dist;"),
                ForgeEnvironmentCheck.missing(loaderJar(true, true, true, false), distJar()));
    }

    /** A class file the JDK cannot parse is reported with the parser's exception, never a bare or null message. */
    @Test
    void anUnparsableClassIsReportedWithItsException() throws IOException {
        Path broken = jar("broken.jar", Map.of("net/minecraftforge/api/distmarker/Dist.class", new byte[] {1, 2, 3}));
        IOException thrown =
                assertThrows(IOException.class, () -> ForgeEnvironmentCheck.missing(loaderJar(true, true), broken));
        assertTrue(
                thrown.getMessage()
                        .startsWith("Cannot parse class file in Forge's fmlloader or mergetool-api jar: "
                                + IllegalArgumentException.class.getName()),
                thrown::getMessage);
    }

    private static Path only(List<Path> paths, String prefix) {
        List<Path> found = paths.stream()
                .filter(path -> path.getFileName().toString().startsWith(prefix))
                .toList();
        assertEquals(1, found.size(), () -> prefix + " in " + paths);
        return found.getFirst();
    }

    private Path distJar() throws IOException {
        byte[] dist = ClassFile.of().build(DIST, type -> {
            type.withSuperclass(ClassDesc.of("java.lang.Enum"));
            for (String side : ForgeEnvironmentCheck.SIDES) {
                type.withField(side, DIST, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
            }
        });
        return jar("dist.jar", Map.of("net/minecraftforge/api/distmarker/Dist.class", dist));
    }

    /**
     * Forge's {@code FMLLoader} and {@code FMLEnvironment} as javap shows them for Forge 26.2-65.1.5, or with {@code
     * getGamePath()} computing its value, or an {@code FMLEnvironment} that does not copy the side.
     */
    private Path loaderJar(boolean plainGetter, boolean copiesSide) throws IOException {
        return loaderJar(plainGetter, copiesSide, true, true);
    }

    /** As {@link #loaderJar(boolean, boolean)}, with {@code getDist()} not static, or {@code FMLEnvironment} without its field. */
    private Path loaderJar(boolean plainGetter, boolean copiesSide, boolean staticGetDist, boolean environmentField)
            throws IOException {
        byte[] loader = ClassFile.of().build(LOADER, type -> {
            type.withField("dist", DIST, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC);
            type.withField("gamePath", PATH, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC);
            int getter = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC;
            type.withMethodBody(
                    "getDist",
                    MethodTypeDesc.of(DIST),
                    staticGetDist ? getter : ClassFile.ACC_PUBLIC,
                    code -> code.getstatic(LOADER, "dist", DIST).areturn());
            type.withMethodBody("getGamePath", MethodTypeDesc.of(PATH), getter, code -> {
                code.getstatic(LOADER, "gamePath", PATH);
                if (!plainGetter) {
                    code.invokeinterface(PATH, "normalize", MethodTypeDesc.of(PATH));
                }
                code.areturn();
            });
        });
        byte[] environment = ClassFile.of().build(ENVIRONMENT, type -> {
            if (environmentField) {
                type.withField("dist", DIST, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
            }
            type.withMethodBody("<clinit>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC, code -> {
                if (copiesSide) {
                    code.invokestatic(LOADER, "getDist", MethodTypeDesc.of(DIST));
                } else {
                    code.aconst_null();
                }
                code.putstatic(ENVIRONMENT, "dist", DIST);
                code.return_();
            });
        });
        return jar(
                "fmlloader.jar",
                Map.of(
                        ForgeEnvironmentCheck.FML_LOADER + ".class",
                        loader,
                        "net/minecraftforge/fml/loading/FMLEnvironment.class",
                        environment));
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
