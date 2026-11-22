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
 * Forge on NeoForge, F-N4: the host's patch of Forge's {@code ForgeRegistries} in the {@code universal} jars of the Forge
 * releases Plexus Mod Loader pins for Minecraft 26.1, 26.1.1, 26.1.2, 26.2 and 26.3 ({@code plexus.forgeEventJars}).
 */
class ForgeRegistriesPatchTest {

    @ParameterizedTest
    @ValueSource(strings = {"26.1", "26.1.1", "26.1.2", "26.2", "26.3"})
    void theRegistrySetUpGoesToTheHostAndNothingElseChanges(String minecraft) throws IOException {
        byte[] original = forgeRegistries(minecraft);
        ForgeRegistriesPatch.Calls before = ForgeRegistriesPatch.calls(original);
        assertTrue(before.fit(), before::toString);

        byte[] patched = ForgeRegistriesPatch.apply(original);

        assertEquals(new ForgeRegistriesPatch.Calls(0, 0, 0), ForgeRegistriesPatch.calls(patched));
        ClassModel originalClass = ClassFile.of().parse(original);
        ClassModel patchedClass = ClassFile.of().parse(patched);
        assertEquals(before.init(), hookCalls(patchedClass, "initForgeRegistries", "()V"));
        assertEquals(
                before.active(), hookCalls(patchedClass, "gameRegistry", "(Ljava/lang/Object;)Ljava/lang/Object;"));
        assertEquals(originalClass.methods().size(), patchedClass.methods().size());
        for (int i = 0; i < originalClass.methods().size(); i++) {
            int index = i;
            String name = originalClass.methods().get(i).methodName().stringValue();
            // Each redirected active(ResourceKey) call gets a three-byte checkcast to IForgeRegistry after it.
            int added = name.equals("<clinit>") ? 3 * before.active() : 0;
            assertEquals(
                    codeLength(originalClass.methods().get(i)) + added,
                    codeLength(patchedClass.methods().get(i)),
                    () -> originalClass.methods().get(index).methodName().stringValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"26.2"})
    void aClassWithoutTheCallsIsRefused(String minecraft) throws IOException {
        byte[] patched = ForgeRegistriesPatch.apply(forgeRegistries(minecraft));
        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> ForgeRegistriesPatch.apply(patched));
        assertTrue(refused.getMessage().contains("0 init() and 0 active(ResourceKey)"), refused::getMessage);
    }

    private static long hookCalls(ClassModel model, String name, String type) {
        return model.methods().stream()
                .flatMap(method -> method.code().stream())
                .flatMap(code -> code.elementStream())
                .filter(element -> element instanceof InvokeInstruction invoke
                        && invoke.owner().asInternalName().equals(ForgeRegistryObjectPatch.HOOKS)
                        && invoke.name().equalsString(name)
                        && invoke.type().equalsString(type))
                .count();
    }

    private static int codeLength(MethodModel method) {
        return method.findAttribute(Attributes.code())
                .map(CodeAttribute::codeLength)
                .orElse(0);
    }

    private static byte[] forgeRegistries(String minecraft) throws IOException {
        try (JarFile jar = new JarFile(forgeJar(minecraft).toFile());
                InputStream input = jar.getInputStream(jar.getJarEntry(ForgeRegistriesPatch.CLASS_FILE))) {
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
