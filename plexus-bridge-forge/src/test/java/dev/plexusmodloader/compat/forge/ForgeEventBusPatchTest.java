package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Forge on NeoForge, F-N6: the host's patch of Forge's {@code Mod.EventBusSubscriber.Bus} in the {@code javafmllanguage}
 * jars of the Forge releases Plexus Mod Loader pins for Minecraft 26.2 and 26.3 ({@code plexus.forgeEnvironment262},
 * {@code plexus.forgeEnvironment263}).
 */
class ForgeEventBusPatchTest {

    @ParameterizedTest
    @ValueSource(strings = {"plexus.forgeEnvironment262", "plexus.forgeEnvironment263"})
    void theForgeBusCallGoesToTheHostAndNothingElseChanges(String property) throws IOException {
        Path jar = javafmllanguage(property);
        assertEquals(1, ForgeEventBusPatch.calls(jar));
        byte[] original = bus(jar);

        byte[] patched = ForgeEventBusPatch.apply(original);

        assertEquals(0, ForgeEventBusPatch.calls(patched));
        ClassModel before = ClassFile.of().parse(original);
        ClassModel after = ClassFile.of().parse(patched);
        long hooks = after.methods().stream()
                .flatMap(method -> method.code().stream())
                .flatMap(code -> code.elementStream())
                .filter(element -> element instanceof InvokeInstruction invoke
                        && invoke.owner().asInternalName().equals(ForgeEventBusPatch.HOOKS)
                        && invoke.name().equalsString("forgeBus"))
                .count();
        assertEquals(1, hooks);
        assertEquals(before.methods().size(), after.methods().size());
        for (int i = 0; i < before.methods().size(); i++) {
            int index = i;
            assertEquals(
                    codeLength(before.methods().get(i)),
                    codeLength(after.methods().get(i)),
                    () -> before.methods().get(index).methodName().stringValue());
        }
        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> ForgeEventBusPatch.apply(patched));
        assertTrue(refused.getMessage().contains("0 call(s)"), refused::getMessage);
    }

    private static int codeLength(MethodModel method) {
        return method.findAttribute(Attributes.code())
                .map(CodeAttribute::codeLength)
                .orElse(0);
    }

    private static byte[] bus(Path javafmllanguage) throws IOException {
        try (JarFile jar = new JarFile(javafmllanguage.toFile());
                InputStream input = jar.getInputStream(jar.getJarEntry(ForgeEventBusPatch.CLASS_FILE))) {
            return input.readAllBytes();
        }
    }

    private static Path javafmllanguage(String property) {
        String jars = System.getProperty(property);
        assertNotNull(jars, "the build names Forge's javafmllanguage jar in " + property);
        return Arrays.stream(jars.split(File.pathSeparator))
                .map(Path::of)
                .filter(jar -> jar.getFileName().toString().startsWith("javafmllanguage-"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No javafmllanguage-* in " + jars));
    }
}
