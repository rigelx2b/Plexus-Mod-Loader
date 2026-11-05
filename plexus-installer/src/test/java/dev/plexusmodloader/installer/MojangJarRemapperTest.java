package dev.plexusmodloader.installer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MojangJarRemapperTest {

    @TempDir
    Path folder;

    @Test
    void renamesClassesMembersAndReferencesThroughSubclasses() throws Exception {
        Path input = folder.resolve("client.jar");
        SyntheticGame.write(input);
        Path output = folder.resolve("out/client-mojang.jar");

        MojangJarRemapper.Result result = MojangJarRemapper.remap(
                input, MojangMappings.parse(SyntheticGame.MAPPINGS.lines().toList()), List.of(), output);

        assertEquals(3, result.classCount());
        assertEquals(3, result.renamedClassCount());
        assertEquals(List.of(), result.unlistedClasses());
        assertEquals(0, result.unknownTypeMerges());
        assertEquals(List.of("META-INF/MOJANGCS.RSA", "META-INF/MOJANGCS.SF"), result.removedSignatureFiles());
        try (JarFile jar = new JarFile(output.toFile())) {
            assertNotNull(jar.getEntry("net/minecraft/world/Thing.class"));
            assertNotNull(jar.getEntry("net/minecraft/world/Sub.class"));
            assertNotNull(jar.getEntry("net/minecraft/world/Other.class"));
            assertEquals(null, jar.getEntry("a.class"));
            // Signatures no longer match the classes; resources and the main class stay.
            assertEquals(null, jar.getEntry("META-INF/MOJANGCS.SF"));
            assertEquals(
                    "net.minecraft.Main", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            try (InputStream version = jar.getInputStream(jar.getEntry("version.json"))) {
                assertEquals(SyntheticGame.VERSION_JSON, new String(version.readAllBytes()));
            }
        }
        assertEquals(List.of(), RemappedGameJar.RemappedJarCheck.problems(output, mappings(), result));
    }

    /** The remapped classes must verify and run: the inherited field, method and the merged frame all resolve. */
    @Test
    void theRemappedClassesLoadVerifyAndRun() throws Exception {
        Path input = folder.resolve("client.jar");
        SyntheticGame.write(input);
        Path output = folder.resolve("client-mojang.jar");
        MojangJarRemapper.remap(input, mappings(), List.of(), output);

        try (JarClassLoader loader = new JarClassLoader(output)) {
            Class<?> sub = loader.loadClass("net.minecraft.world.Sub");
            Object instance = sub.getConstructor().newInstance();
            Method update = sub.getMethod("update");
            update.invoke(instance);
            assertEquals(1, sub.getField("count").getInt(instance));
            assertEquals("net.minecraft.world.Thing", sub.getSuperclass().getName());
            sub.getMethod("tick").invoke(instance);
        }
    }

    @Test
    void theSameInputGivesTheSameBytes() throws Exception {
        Path input = folder.resolve("client.jar");
        SyntheticGame.write(input);
        Path first = folder.resolve("first.jar");
        Path second = folder.resolve("second.jar");

        MojangJarRemapper.remap(input, mappings(), List.of(), first);
        MojangJarRemapper.remap(input, mappings(), List.of(), second);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void mappingsOfAnotherVersionFailTheCheck() throws Exception {
        Path input = folder.resolve("client.jar");
        SyntheticGame.write(input);
        Path output = folder.resolve("client-mojang.jar");
        MojangMappings unrelated =
                MojangMappings.parse(SyntheticGame.UNRELATED_MAPPINGS.lines().toList());

        MojangJarRemapper.Result result = MojangJarRemapper.remap(input, unrelated, List.of(), output);
        List<String> problems = RemappedGameJar.RemappedJarCheck.problems(output, unrelated, result);

        assertEquals(0, result.renamedClassCount());
        assertEquals(1, result.absentListedClassCount());
        assertTrue(
                problems.stream().anyMatch(problem -> problem.contains("none of its 3 classes")), problems.toString());
        assertTrue(
                problems.stream().anyMatch(problem -> problem.contains("keep their obfuscated names")),
                problems.toString());
    }

    @Test
    void parsesMethodDescriptorsWithMojangTypes() {
        MojangMappings mappings = MojangMappings.parse(List.of(
                "net.minecraft.world.Thing -> a:",
                "    1:1:net.minecraft.world.Thing[] copy(net.minecraft.world.Thing,int[],long) -> g",
                "    void inlined.name() -> h",
                "net.minecraft.world.Other -> f:"));

        assertEquals("copy", mappings.listedMethod("a", "g", "(La;[IJ)[La;"));
        assertEquals(null, mappings.listedMethod("a", "h", "()V"));
        assertEquals("net/minecraft/world/Other", mappings.className("f"));
        assertFalse(mappings.listsClass("g"));
    }

    private MojangMappings mappings() {
        return MojangMappings.parse(SyntheticGame.MAPPINGS.lines().toList());
    }

    /** Defines the classes of one jar, child-first, so the test sees exactly what the remap wrote. */
    private static final class JarClassLoader extends ClassLoader implements AutoCloseable {

        private final Map<String, byte[]> classes;

        JarClassLoader(Path jar) {
            super(MojangJarRemapperTest.class.getClassLoader());
            Map<String, byte[]> read = MojangJarRemapper.readClasses(jar);
            this.classes = read;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        public void close() throws IOException {
            // Nothing is open; the jar was read completely.
        }
    }
}
