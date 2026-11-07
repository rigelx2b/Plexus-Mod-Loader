package dev.plexusmodloader.compat.fabric.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.plexusmodloader.compat.fabric.intermediary.IntermediaryTranslations;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code getMappingResolver()} on a game before 26.1 answers for the intermediary namespace (ADR-0037). */
class IntermediaryMappingResolverTest {

    @AfterEach
    void clean() {
        System.clearProperty("plexus.gameJar");
        // The environment of IntermediaryTranslations is remembered per JVM; forget it for the other tests.
        IntermediaryTranslations.reset();
    }

    private static void game(Path temp) throws IOException {
        Path folder = Files.createDirectories(temp.resolve("game"));
        Path jar = folder.resolve("client-1.21.1-mojang.jar");
        try (OutputStream file = Files.newOutputStream(jar);
                ZipOutputStream out = new ZipOutputStream(file)) {
            out.putNextEntry(new ZipEntry("version.json"));
            out.write("{\"id\":\"1.21.1\"}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Files.writeString(
                folder.resolve("client-mappings-1.21.1.txt"),
                String.join(
                        "\n",
                        "net.minecraft.world.Level -> a:",
                        "    int count -> a",
                        "    int size() -> b",
                        "net.minecraft.server.Other -> b:",
                        ""));
        try (OutputStream file = Files.newOutputStream(folder.resolve("intermediary-1.21.1-v2.jar"));
                ZipOutputStream out = new ZipOutputStream(file)) {
            out.putNextEntry(new ZipEntry("mappings/mappings.tiny"));
            out.write(String.join(
                            "\n",
                            "tiny\t2\t0\tofficial\tintermediary",
                            "c\ta\tnet/minecraft/class_1",
                            "\tf\tI\ta\tfield_10",
                            "\tm\t()I\tb\tmethod_21",
                            "c\tb\tnet/minecraft/class_2",
                            "")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        System.setProperty("plexus.gameJar", jar.toString());
        // An earlier test of this JVM may have looked at the game without these files.
        IntermediaryTranslations.reset();
    }

    @Test
    void anObfuscatedGameAnswersForIntermediary(@TempDir Path temp) throws IOException {
        game(temp);
        OfficialMappingResolver resolver = new OfficialMappingResolver();

        assertEquals(Set.of("official", "intermediary"), Set.copyOf(resolver.getNamespaces()));
        assertEquals("official", resolver.getCurrentRuntimeNamespace());
        assertEquals("net.minecraft.world.Level", resolver.mapClassName("intermediary", "net.minecraft.class_1"));
        assertEquals("net.minecraft.class_2", resolver.unmapClassName("intermediary", "net.minecraft.server.Other"));
        assertEquals("count", resolver.mapFieldName("intermediary", "net.minecraft.class_1", "field_10", "I"));
        assertEquals("size", resolver.mapMethodName("intermediary", "net.minecraft.class_1", "method_21", "()I"));
        // Other namespaces and names the mappings do not know come back unchanged.
        assertEquals("net.minecraft.class_1", resolver.mapClassName("official", "net.minecraft.class_1"));
        assertEquals("net.example.Thing", resolver.mapClassName("intermediary", "net.example.Thing"));
        assertThrows(
                IllegalArgumentException.class, () -> resolver.mapClassName("intermediary", "net/minecraft/class_1"));
    }

    @Test
    void withoutAnObfuscatedGameNothingIsTranslated() {
        OfficialMappingResolver resolver = new OfficialMappingResolver();

        assertEquals(Set.of("official"), Set.copyOf(resolver.getNamespaces()));
        assertEquals("net.minecraft.class_1", resolver.mapClassName("intermediary", "net.minecraft.class_1"));
        assertEquals("method_21", resolver.mapMethodName("intermediary", "net.minecraft.class_1", "method_21", "()I"));
    }
}
