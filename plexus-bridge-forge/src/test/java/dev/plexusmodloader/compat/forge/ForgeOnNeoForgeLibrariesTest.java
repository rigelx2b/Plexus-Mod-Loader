package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.plexusmodloader.api.runtime.GameSide;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Forge on NeoForge, F-N2: Forge's API jars for NeoForge's loader libraries, and which Forge mods the host constructs. */
class ForgeOnNeoForgeLibrariesTest {

    private static final String VERSION = "26.2-65.1.5";

    @TempDir
    Path directory;

    @Test
    void selectTakesOneJarPerPrefixAndForgesUniversalJar() throws IOException {
        List<Path> libraries = List.of(
                Path.of("net/minecraftforge/eventbus/7.0.5/eventbus-7.0.5.jar"),
                Path.of("net/minecraftforge/forgespi/8.0.0/forgespi-8.0.0.jar"),
                Path.of("net/minecraftforge/fmlcore/" + VERSION + "/fmlcore-" + VERSION + ".jar"),
                Path.of("net/minecraftforge/javafmllanguage/" + VERSION + "/javafmllanguage-" + VERSION + ".jar"),
                Path.of("net/minecraftforge/securemodules/2.2.24/securemodules-2.2.24.jar"),
                Path.of("net/minecraftforge/unsafe/0.9.2/unsafe-0.9.2.jar"),
                Path.of("net/minecraftforge/fmlloader/" + VERSION + "/fmlloader-" + VERSION + ".jar"),
                Path.of("net/minecraftforge/forge/" + VERSION + "/forge-" + VERSION + "-universal.jar"),
                Path.of("org/ow2/asm/asm/9.10.1/asm-9.10.1.jar"));
        List<Path> selected = ForgeOnNeoForgeLibraries.select(libraries, VERSION);
        assertEquals(
                List.of(
                        "eventbus-7.0.5.jar",
                        "forgespi-8.0.0.jar",
                        "fmlcore-" + VERSION + ".jar",
                        "javafmllanguage-" + VERSION + ".jar",
                        "securemodules-2.2.24.jar",
                        "unsafe-0.9.2.jar",
                        "forge-" + VERSION + "-universal.jar"),
                selected.stream().map(jar -> jar.getFileName().toString()).toList());
    }

    @Test
    void selectFailsWhenAJarIsMissing() {
        assertThrows(
                IOException.class,
                () -> ForgeOnNeoForgeLibraries.select(List.of(Path.of("eventbus-7.0.5.jar")), VERSION));
    }

    @Test
    void aJavafmlModWithItsModClassIsConstructed() throws IOException {
        Path jar = jar("javafml", "example", true);
        assertEquals(List.of(), ForgeOnNeoForgeLibraries.languageReasons(jar));
    }

    /** A data-only jar declares a mod but has no {@code @Mod} class; FML's language loader would find none to build. */
    @Test
    void aModWithoutModClassIsDeclined() throws IOException {
        Path jar = jar("javafml", "example", false);
        assertEquals(List.of("@Mod example"), ForgeOnNeoForgeLibraries.languageReasons(jar));
    }

    /** Another language provider's mod (Kotlin for Forge) is not constructed by Forge's Java mod container. */
    @Test
    void anotherLanguageLoaderIsDeclined() throws IOException {
        Path jar = jar("kotlinforforge", "example", true);
        assertEquals(List.of("modLoader kotlinforforge"), ForgeOnNeoForgeLibraries.languageReasons(jar));
    }

    /**
     * A mod declined by {@link ForgeOnNeoForgeLibraries#languageReasons} after the requirement check (a data-only jar
     * without its {@code @Mod} class) leaves out a mod that requires it, which FML would refuse (it would stop the whole
     * game): the check runs again on what is left until nothing more is declined. A mod that requires nothing declined
     * stays.
     */
    @Test
    void aModThatRequiresADeclinedModIsDeclinedToo() throws Exception {
        Path dataOnly = jar("javafml", "data_only", false);
        Path needsData = jar("javafml", "needs_data", true, "data_only");
        Path alone = jar("javafml", "alone", true);
        List<String> log = new ArrayList<>();
        Path mavenArtifact = Path.of(Class.forName("org.apache.maven.artifact.versioning.VersionRange")
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());

        ForgeOnNeoForgeLibraries.Checked checked = ForgeOnNeoForgeLibraries.check(
                "26.2",
                GameSide.DEDICATED_SERVER,
                log::add,
                mavenArtifact,
                "65.1.5",
                List.of(dataOnly, needsData, alone));

        assertEquals(List.of(alone), checked.kept());
        assertEquals(
                List.of(dataOnly, needsData), List.copyOf(checked.declined().keySet()));
        assertEquals(List.of("@Mod data_only"), checked.declined().get(dataOnly));
        assertEquals(2, log.size(), log::toString);
    }

    private Path jar(String modLoader, String modId, boolean modClass) throws IOException {
        return jar(modLoader, modId, modClass, null);
    }

    private Path jar(String modLoader, String modId, boolean modClass, String requires) throws IOException {
        String toml = "modLoader = \"" + modLoader + "\"\nloaderVersion = \"[65,)\"\nlicense = \"MIT\"\n\n[[mods]]\n"
                + "modId = \"" + modId + "\"\nversion = \"1.0.0\"\n";
        if (requires != null) {
            toml += "\n[[dependencies." + modId + "]]\nmodId = \"" + requires + "\"\nmandatory = true\n"
                    + "versionRange = \"*\"\nordering = \"NONE\"\nside = \"BOTH\"\n";
        }
        Path jar = directory.resolve(modLoader + "-" + modId + "-" + modClass + ".jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream output = new JarOutputStream(out)) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write(toml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            if (modClass) {
                byte[] bytes = ClassFile.of()
                        .build(
                                ClassDesc.of("example.Example"),
                                type -> type.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(
                                        ClassDesc.ofInternalName("net/minecraftforge/fml/common/Mod"),
                                        AnnotationElement.of("value", AnnotationValue.ofString(modId))))));
                output.putNextEntry(new JarEntry("example/Example.class"));
                output.write(bytes);
                output.closeEntry();
            }
        }
        return jar;
    }
}
