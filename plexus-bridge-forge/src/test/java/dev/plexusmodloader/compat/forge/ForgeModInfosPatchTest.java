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
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Forge on NeoForge (ADR-0051 24): the host's patch of Forge's {@code ModList.getMods()} in the {@code fmlcore} jars of
 * the Forge releases Plexus Mod Loader pins for Minecraft 26.2 and 26.3.
 */
class ForgeModInfosPatchTest {

    @ParameterizedTest
    @ValueSource(strings = {"plexus.forgeEnvironment262", "plexus.forgeEnvironment263"})
    void getModsReadsTheHostAndNothingElseChanges(String property) throws IOException {
        Path jar = fmlcore(property);
        assertEquals(1, ForgeModInfosPatch.reads(jar));
        byte[] original = classFile(jar);

        byte[] patched = ForgeModInfosPatch.apply(original);

        assertEquals(0, ForgeModInfosPatch.reads(patched));
        ClassModel before = ClassFile.of().parse(original);
        ClassModel after = ClassFile.of().parse(patched);
        MethodModel getMods = method(after, ForgeModInfosPatch.METHOD);
        List<CodeElement> code = getMods.code().orElseThrow().elementList();
        assertEquals(
                1,
                code.stream()
                        .filter(element -> element instanceof InvokeInstruction invoke
                                && invoke.owner().asInternalName().equals(ForgeModInfosPatch.HOOKS)
                                && invoke.name().equalsString(ForgeModInfosPatch.HOOK)
                                && invoke.type().equalsString(ForgeModInfosPatch.METHOD_DESCRIPTOR))
                        .count());
        assertEquals(0, code.stream().filter(FieldInstruction.class::isInstance).count());
        assertEquals(before.methods().size(), after.methods().size());
        for (int i = 0; i < before.methods().size(); i++) {
            int index = i;
            assertEquals(
                    codeLength(before.methods().get(i)),
                    codeLength(after.methods().get(i)),
                    () -> before.methods().get(index).methodName().stringValue());
        }
        // Every other method keeps its reads of SORTED_LIST (ModList's static initializer fills it).
        long otherReads = after.methods().stream()
                .filter(method -> !method.methodName().equalsString(ForgeModInfosPatch.METHOD))
                .flatMap(method -> method.code().stream())
                .flatMap(body -> body.elementStream())
                .filter(element -> element instanceof FieldInstruction field
                        && field.name().equalsString(ForgeModInfosPatch.FIELD))
                .count();
        long otherReadsBefore = before.methods().stream()
                .filter(method -> !method.methodName().equalsString(ForgeModInfosPatch.METHOD))
                .flatMap(method -> method.code().stream())
                .flatMap(body -> body.elementStream())
                .filter(element -> element instanceof FieldInstruction field
                        && field.name().equalsString(ForgeModInfosPatch.FIELD))
                .count();
        assertEquals(otherReadsBefore, otherReads);
        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> ForgeModInfosPatch.apply(patched));
        assertTrue(refused.getMessage().contains("0 read(s)"), refused::getMessage);
    }

    private static MethodModel method(ClassModel model, String name) {
        return model.methods().stream()
                .filter(method -> method.methodName().equalsString(name))
                .findFirst()
                .orElseThrow();
    }

    private static int codeLength(MethodModel method) {
        return method.findAttribute(Attributes.code())
                .map(CodeAttribute::codeLength)
                .orElse(0);
    }

    private static byte[] classFile(Path fmlcore) throws IOException {
        try (JarFile jar = new JarFile(fmlcore.toFile());
                InputStream input = jar.getInputStream(jar.getJarEntry(ForgeModInfosPatch.CLASS_FILE))) {
            return input.readAllBytes();
        }
    }

    private static Path fmlcore(String property) {
        String jars = System.getProperty(property);
        assertNotNull(jars, "the build names Forge's fmlcore jar in " + property);
        return Arrays.stream(jars.split(File.pathSeparator))
                .map(Path::of)
                .filter(jar -> jar.getFileName().toString().startsWith("fmlcore-"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No fmlcore-* in " + jars));
    }
}
