package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Against FML's own {@code LoadingModList} from {@code loader} 4.0.45 (NeoForge 21.1) and 2.0.17 (NeoForge 20.4), read
 * from the jars the build names in {@code plexus.fml4Loader} and {@code plexus.fml2Loader} (ADR-0043 point 10).
 */
class Fml4SharedMixinPatchTest {

    private static final ClassDesc REGISTRATION =
            ClassDesc.of("net.neoforged.fml.loading.mixin.DeferredMixinConfigRegistration");

    private static final Map<String, Map<String, byte[]>> JARS = new HashMap<>();

    @ParameterizedTest
    @ValueSource(strings = {"plexus.fml4Loader", "plexus.fml2Loader"})
    void fmlHasTheCheckedShape(String jar) {
        assertEquals(List.of(), Fml4SharedMixinPatch.missing(classes(jar).get(Fml4SharedMixinPatch.CLASS_FILE)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"plexus.fml4Loader", "plexus.fml2Loader"})
    void theRegistrationCallGoesToTheHostAndTheClassStillVerifies(String jar) {
        ClassHierarchyResolver hierarchy = hierarchy(jar);
        Fml4SharedMixinPatch.Result result =
                Fml4SharedMixinPatch.apply(classes(jar).get(Fml4SharedMixinPatch.CLASS_FILE), hierarchy);

        assertTrue(result.missing().isEmpty(), () -> "missing: " + result.missing());
        assertEquals(1, result.changed().size(), () -> "changed: " + result.changed());
        ClassModel patched = ClassFile.of().parse(result.bytes());
        assertEquals(0, calls(patched, REGISTRATION, "addMixinConfig"));
        assertEquals(1, calls(patched, Fml4SharedMixinPatch.HOST, Fml4SharedMixinPatch.HOST_METHOD));
        List<VerifyError> errors = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchy))
                .verify(result.bytes());
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {"plexus.fml4Loader", "plexus.fml2Loader"})
    void otherClassesStayAsTheyAre(String jar) {
        byte[] other = classes(jar).get("net/neoforged/fml/loading/FMLLoader.class");
        assertNotNull(other);
        Fml4SharedMixinPatch.Result result = Fml4SharedMixinPatch.apply(other, hierarchy(jar));
        assertArrayEquals(other, result.bytes());
        assertTrue(result.changed().isEmpty());
        assertFalse(Fml4SharedMixinPatch.missing(other).isEmpty());
    }

    private static int calls(ClassModel model, ClassDesc owner, String name) {
        int calls = 0;
        for (var method : model.methods()) {
            for (var element : method.code().map(code -> code.elementList()).orElse(List.of())) {
                if (element instanceof InvokeInstruction call
                        && call.owner().asSymbol().equals(owner)
                        && call.name().equalsString(name)) {
                    calls++;
                }
            }
        }
        return calls;
    }

    /** The jar's classes first, then the test class path's (JDK, SLF4J, ...). */
    private static ClassHierarchyResolver hierarchy(String jar) {
        Map<String, byte[]> classes = classes(jar);
        ClassHierarchyResolver own = ClassHierarchyResolver.ofResourceParsing(desc -> {
            String name = desc.descriptorString();
            byte[] bytes = classes.get(name.substring(1, name.length() - 1) + ".class");
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        });
        return own.orElse(ClassHierarchyResolver.ofResourceParsing(Fml4SharedMixinPatchTest.class.getClassLoader()))
                .orElse(ClassHierarchyResolver.defaultResolver());
    }

    private static synchronized Map<String, byte[]> classes(String property) {
        return JARS.computeIfAbsent(property, key -> {
            String jar = System.getProperty(key);
            assertNotNull(jar, "the build names FML's loader jar in " + key);
            Map<String, byte[]> classes = new HashMap<>();
            try (JarFile file = new JarFile(Path.of(jar).toFile())) {
                for (var entry : file.stream().toList()) {
                    if (entry.getName().endsWith(".class")) {
                        try (InputStream in = file.getInputStream(entry)) {
                            classes.put(entry.getName(), in.readAllBytes());
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return classes;
        });
    }
}
