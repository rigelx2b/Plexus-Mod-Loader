package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Forge on NeoForge, F-N4: the host's patch of Forge's {@code RegistryObject} in the {@code universal} jars of the Forge
 * releases Plexus Mod Loader pins for Minecraft 26.2 and 26.3 ({@code plexus.forgeEventJars}).
 */
class ForgeRegistryObjectPatchTest {

    @ParameterizedTest
    @ValueSource(strings = {"26.2", "26.3"})
    void theOneIsIntrusiveCallGoesToTheHostAndNothingElseChanges(String minecraft) throws IOException {
        byte[] original = registryObject(minecraft);
        assertEquals(1, ForgeRegistryObjectPatch.calls(original));

        byte[] patched = ForgeRegistryObjectPatch.apply(original);

        assertEquals(0, ForgeRegistryObjectPatch.calls(patched));
        ClassModel before = ClassFile.of().parse(original);
        ClassModel after = ClassFile.of().parse(patched);
        assertEquals(before.methods().size(), after.methods().size());
        long hooks = after.methods().stream()
                .flatMap(method -> method.code().stream())
                .flatMap(code -> code.elementStream())
                .filter(element -> element instanceof InvokeInstruction invoke
                        && invoke.owner().asInternalName().equals(ForgeRegistryObjectPatch.HOOKS)
                        && invoke.name().equalsString("isIntrusive")
                        && invoke.type().equalsString("(Ljava/lang/Object;)Z"))
                .count();
        assertEquals(1, hooks);
        for (int i = 0; i < before.methods().size(); i++) {
            int index = i;
            int beforeLength = codeLength(before.methods().get(i));
            int afterLength = codeLength(after.methods().get(i));
            assertEquals(
                    beforeLength,
                    afterLength,
                    () -> before.methods().get(index).methodName().stringValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"26.2"})
    void aClassWithoutTheCallIsRefused(String minecraft) throws IOException {
        byte[] patched = ForgeRegistryObjectPatch.apply(registryObject(minecraft));
        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> ForgeRegistryObjectPatch.apply(patched));
        assertTrue(refused.getMessage().contains("0 call(s)"), refused::getMessage);
    }

    private static int codeLength(MethodModel method) {
        return method.findAttribute(Attributes.code())
                .map(CodeAttribute::codeLength)
                .orElse(0);
    }

    private static byte[] registryObject(String minecraft) throws IOException {
        try (JarFile jar = new JarFile(forgeJar(minecraft).toFile());
                InputStream input = jar.getInputStream(jar.getJarEntry(ForgeRegistryObjectPatch.CLASS_FILE))) {
            return input.readAllBytes();
        }
    }

    private static Path forgeJar(String minecraft) {
        String jars = System.getProperty("plexus.forgeEventJars");
        String prefix = "forge-" + minecraft + "-";
        return Arrays.stream(jars.split(File.pathSeparator))
                .map(Path::of)
                .filter(jar -> jar.getFileName().toString().startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + prefix + "* in " + jars));
    }
}
