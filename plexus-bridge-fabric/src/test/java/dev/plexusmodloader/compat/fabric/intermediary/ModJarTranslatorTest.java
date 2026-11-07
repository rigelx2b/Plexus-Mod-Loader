package dev.plexusmodloader.compat.fabric.intermediary;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class ModJarTranslatorTest {

    private static final String LEVEL = "net/minecraft/class_1";

    /** A Mixin-like class: extends a game class, overrides one of its methods, uses another and a field. */
    static byte[] mixinClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "x/M", null, LEVEL, null);
        writer.visitField(Opcodes.ACC_PRIVATE, "field_10", "I", null, null).visitEnd();
        MethodVisitor override = writer.visitMethod(Opcodes.ACC_PUBLIC, "method_21", "()I", null, null);
        AnnotationVisitor inject = override.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor selectors = inject.visitArray("method");
        selectors.visit(null, "method_21");
        selectors.visitEnd();
        inject.visitEnd();
        override.visitCode();
        override.visitVarInsn(Opcodes.ALOAD, 0);
        override.visitMethodInsn(Opcodes.INVOKESPECIAL, LEVEL, "method_21", "()I", false);
        override.visitVarInsn(Opcodes.ALOAD, 0);
        override.visitFieldInsn(Opcodes.GETFIELD, LEVEL, "field_10", "I");
        override.visitInsn(Opcodes.IADD);
        override.visitLdcInsn("net.minecraft.class_1");
        override.visitInsn(Opcodes.POP);
        override.visitInsn(Opcodes.IRETURN);
        override.visitMaxs(0, 0);
        override.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] plainClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "x/Plain", null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    static Map<String, byte[]> fullMod() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("fabric.mod.json", """
                {"schemaVersion":1,"id":"m","version":"1","mixins":["m.mixins.json"],"accessWidener":"m.accesswidener"}
                """.getBytes(StandardCharsets.UTF_8));
        files.put("m.mixins.json", """
                {"package":"x","refmap":"m-refmap.json","mixins":["M"]}
                """.getBytes(StandardCharsets.UTF_8));
        files.put("m-refmap.json", """
                {"mappings":{"x/M":{"tick":"Lnet/minecraft/class_1;method_21()I"}},"data":{"named:intermediary":{"x/M":{"tick":"method_21"}}}}
                """.getBytes(StandardCharsets.UTF_8));
        files.put(
                "m.accesswidener",
                String.join(
                                "\n",
                                "accessWidener\tv2\tintermediary",
                                "# a comment",
                                "accessible\tclass\tnet/minecraft/class_1",
                                "transitive-accessible\tmethod\tnet/minecraft/class_1\tmethod_21\t()I",
                                "mutable  field  net/minecraft/class_1  field_10  I  # why",
                                "extendable\tmethod\tnet/minecraft/class_1\ttoString\t()Ljava/lang/String;",
                                "inject-interface\tnet/minecraft/class_2\tx/Api<Lnet/minecraft/class_1;>",
                                "")
                        .getBytes(StandardCharsets.UTF_8));
        files.put("x/M.class", mixinClass());
        files.put("x/Plain.class", plainClass());
        files.put("assets/m/lang/en_us.json", "{\"a\":\"net.minecraft.class_1\"}".getBytes(StandardCharsets.UTF_8));
        return files;
    }

    static void writeJar(Path jar, Map<String, byte[]> files) throws IOException {
        try (OutputStream file = Files.newOutputStream(jar);
                ZipOutputStream out = new ZipOutputStream(file)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    static byte[] read(Path jar, String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            assertTrue(entry != null, name + " is missing from " + jar);
            return zip.getInputStream(entry).readAllBytes();
        }
    }

    static String text(Path jar, String name) throws IOException {
        return new String(read(jar, name), StandardCharsets.UTF_8);
    }

    @Test
    void classesReferencesAndMixinAnnotationsAreTranslated(@TempDir Path temp) throws IOException {
        Path input = temp.resolve("in.jar");
        Path output = temp.resolve("out.jar");
        writeJar(input, fullMod());

        ModJarTranslator.Result result = ModJarTranslator.translate(TestMappings.translation(), input, output);

        assertTrue(result.changed());
        assertEquals(1, result.classes());
        assertEquals(2, result.resources());
        ClassNode node = new ClassNode();
        new ClassReader(read(output, "x/M.class")).accept(node, 0);
        assertEquals("net/minecraft/world/Level", node.superName);
        assertEquals("count", findField(node, "field_10", "count"));
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("size"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                0, node.methods.stream().filter(m -> m.name.equals("method_21")).count());
        AnnotationNode inject = method.visibleAnnotations.getFirst();
        // The bare name method_21 named one method; "size" alone could name several overloads, so it gets its
        // descriptor.
        assertEquals(java.util.List.of("method", java.util.List.of("size()I")), inject.values);
        MethodInsnNode call = null;
        FieldInsnNode field = null;
        LdcInsnNode ldc = null;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode insn) {
                call = insn;
            } else if (instruction instanceof FieldInsnNode insn) {
                field = insn;
            } else if (instruction instanceof LdcInsnNode insn) {
                ldc = insn;
            }
        }
        assertEquals("net/minecraft/world/Level", call.owner);
        assertEquals("size", call.name);
        assertEquals("count", field.name);
        assertEquals("net.minecraft.world.Level", ldc.cst);
    }

    private static String findField(ClassNode node, String old, String expected) {
        assertTrue(node.fields.stream().noneMatch(field -> field.name.equals(old)), old + " is still declared");
        return node.fields.stream()
                .map(field -> field.name)
                .filter(expected::equals)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void referenceMapAndClassTweakerAreRewrittenAndOtherFilesKept(@TempDir Path temp) throws IOException {
        Path input = temp.resolve("in.jar");
        Path output = temp.resolve("out.jar");
        Map<String, byte[]> files = fullMod();
        writeJar(input, files);

        ModJarTranslator.translate(TestMappings.translation(), input, output);

        JsonObject refmap =
                JsonParser.parseString(text(output, "m-refmap.json")).getAsJsonObject();
        assertEquals(
                "Lnet/minecraft/world/Level;size()I",
                refmap.getAsJsonObject("mappings")
                        .getAsJsonObject("x/M")
                        .get("tick")
                        .getAsString());
        assertEquals(
                "size",
                refmap.getAsJsonObject("data")
                        .getAsJsonObject("named:intermediary")
                        .getAsJsonObject("x/M")
                        .get("tick")
                        .getAsString());
        assertEquals(
                String.join(
                        "\n",
                        "accessWidener\tv2\tofficial",
                        "# a comment",
                        "accessible\tclass\tnet/minecraft/world/Level",
                        "transitive-accessible\tmethod\tnet/minecraft/world/Level\tsize\t()I",
                        "mutable\tfield\tnet/minecraft/world/Level\tcount\tI # why",
                        "extendable\tmethod\tnet/minecraft/world/Level\ttoString\t()Ljava/lang/String;",
                        "inject-interface\tnet/minecraft/server/Other\tx/Api<Lnet/minecraft/world/Level;>",
                        ""),
                text(output, "m.accesswidener"));
        assertArrayEquals(files.get("fabric.mod.json"), read(output, "fabric.mod.json"));
        assertArrayEquals(files.get("m.mixins.json"), read(output, "m.mixins.json"));
        assertArrayEquals(files.get("x/Plain.class"), read(output, "x/Plain.class"));
        assertArrayEquals(files.get("assets/m/lang/en_us.json"), read(output, "assets/m/lang/en_us.json"));
    }

    @Test
    void anAccessorWithoutAValueGetsItsTranslatedTargetAsValue(@TempDir Path temp) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "x/Acc",
                null,
                "java/lang/Object",
                null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", true);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, org.objectweb.asm.Type.getObjectType(LEVEL));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor getter =
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getField_10", "()I", null, null);
        getter.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true)
                .visitEnd();
        getter.visitEnd();
        MethodVisitor explicit =
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "readIt", "()I", null, null);
        AnnotationVisitor named = explicit.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true);
        named.visit("value", "field_10");
        named.visitEnd();
        explicit.visitEnd();
        MethodVisitor caller =
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "callMethod_21", "()I", null, null);
        caller.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Invoker;", true)
                .visitEnd();
        caller.visitEnd();
        MethodVisitor own =
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getSomething", "()I", null, null);
        own.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true).visitEnd();
        own.visitEnd();
        writer.visitEnd();
        Path input = temp.resolve("in.jar");
        Path output = temp.resolve("out.jar");
        writeJar(input, Map.of("x/Acc.class", writer.toByteArray()));

        ModJarTranslator.translate(TestMappings.translation(), input, output);

        ClassNode translated = new ClassNode();
        new ClassReader(read(output, "x/Acc.class")).accept(translated, 0);
        assertEquals(java.util.List.of("value", "count"), accessorValues(translated, "getField_10"));
        assertEquals(java.util.List.of("value", "count"), accessorValues(translated, "readIt"));
        assertEquals(java.util.List.of("value", "size"), invokerValues(translated, "callMethod_21"));
        // A target the mappings do not know gets no value: Mixin infers it as before.
        assertEquals(null, accessorValues(translated, "getSomething"));
    }

    private static java.util.List<Object> accessorValues(ClassNode node, String method) {
        return annotationValues(node, method);
    }

    private static java.util.List<Object> invokerValues(ClassNode node, String method) {
        return annotationValues(node, method);
    }

    private static java.util.List<Object> annotationValues(ClassNode node, String method) {
        MethodNode found = node.methods.stream()
                .filter(candidate -> candidate.name.equals(method))
                .findFirst()
                .orElseThrow();
        return found.visibleAnnotations.getFirst().values;
    }

    @Test
    void aJarWithoutIntermediaryNamesProducesNoCopy(@TempDir Path temp) throws IOException {
        Path input = temp.resolve("in.jar");
        Path output = temp.resolve("out.jar");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("fabric.mod.json", "{\"id\":\"lib\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        files.put("x/Plain.class", plainClass());
        writeJar(input, files);

        ModJarTranslator.Result result = ModJarTranslator.translate(TestMappings.translation(), input, output);

        assertFalse(result.changed());
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(output.resolveSibling("out.jar.partial")));
    }

    @Test
    void signatureFilesAreLeftOutOfACopyAndNestedJarsKept(@TempDir Path temp) throws IOException {
        Path input = temp.resolve("in.jar");
        Path output = temp.resolve("out.jar");
        Map<String, byte[]> files = fullMod();
        files.put("META-INF/MOD.SF", new byte[] {1});
        files.put("META-INF/MOD.RSA", new byte[] {2});
        files.put("META-INF/jars/inner.jar", new byte[] {3, 4, 5});
        writeJar(input, files);

        ModJarTranslator.translate(TestMappings.translation(), input, output);

        java.util.List<String> names = ModJarTranslator.entryNames(output);
        assertFalse(names.contains("META-INF/MOD.SF"));
        assertFalse(names.contains("META-INF/MOD.RSA"));
        assertArrayEquals(new byte[] {3, 4, 5}, read(output, "META-INF/jars/inner.jar"));
    }

    /** A Mixin class whose injector's {@code @At} target is a reference map key in development names. */
    static byte[] modifyVariableMixin() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "x/V", null, "java/lang/Object", null);
        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE, "handler", "(I)I", null, null);
        AnnotationVisitor modify =
                handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", true);
        AnnotationVisitor selectors = modify.visitArray("method");
        selectors.visit(null, "method_21");
        selectors.visitEnd();
        AnnotationVisitor at = modify.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "INVOKE");
        at.visit("target", "Lnet/minecraft/world/World;method_21()I");
        at.visitEnd();
        AnnotationVisitor other = modify.visitAnnotation("slice", "Lorg/spongepowered/asm/mixin/injection/At;");
        other.visit("target", "Lnet/minecraft/class_1;method_21()I");
        other.visitEnd();
        modify.visitEnd();
        handler.visitCode();
        handler.visitVarInsn(Opcodes.ILOAD, 1);
        handler.visitInsn(Opcodes.IRETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void aReferenceMapKeyInAMixinAnnotationStaysAsWritten(@TempDir Path temp) throws IOException {
        Path input = temp.resolve("in.jar");
        Path output = temp.resolve("out.jar");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("fabric.mod.json", """
                {"schemaVersion":1,"id":"v","version":"1","mixins":["v.mixins.json"]}
                """.getBytes(StandardCharsets.UTF_8));
        files.put("v.mixins.json", """
                {"package":"x","refmap":"v-refmap.json","mixins":["V"]}
                """.getBytes(StandardCharsets.UTF_8));
        files.put("v-refmap.json", """
                {"mappings":{"x/V":{"Lnet/minecraft/world/World;method_21()I":"Lnet/minecraft/class_1;method_21()I"}}}
                """.getBytes(StandardCharsets.UTF_8));
        files.put("x/V.class", modifyVariableMixin());
        writeJar(input, files);

        ModJarTranslator.translate(TestMappings.translation(), input, output);

        ClassNode node = new ClassNode();
        new ClassReader(read(output, "x/V.class")).accept(node, 0);
        AnnotationNode modify = node.methods.stream()
                .filter(method -> method.name.equals("handler"))
                .findFirst()
                .orElseThrow()
                .visibleAnnotations
                .getFirst();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < modify.values.size(); i += 2) {
            values.put((String) modify.values.get(i), modify.values.get(i + 1));
        }
        // The key stays, so that Mixin finds its translated value in the reference map.
        assertEquals("Lnet/minecraft/world/World;method_21()I", ((AnnotationNode) values.get("at")).values.get(3));
        // A string that is no key is translated as before.
        assertEquals("Lnet/minecraft/world/Level;size()I", ((AnnotationNode) values.get("slice")).values.get(1));
        assertEquals(
                "Lnet/minecraft/world/Level;size()I",
                JsonParser.parseString(text(output, "v-refmap.json"))
                        .getAsJsonObject()
                        .getAsJsonObject("mappings")
                        .getAsJsonObject("x/V")
                        .get("Lnet/minecraft/world/World;method_21()I")
                        .getAsString());
    }

    @Test
    void anInvokerOfAReusedIntermediaryNameIsTranslatedInItsMixinsTarget(@TempDir Path temp) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "x/I",
                null,
                "java/lang/Object",
                null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, org.objectweb.asm.Type.getObjectType("net/minecraft/class_2"));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor invoker = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "callRun", "(Lnet/minecraft/class_1;)V", null, null);
        AnnotationVisitor value = invoker.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Invoker;", true);
        value.visit("value", "method_20");
        value.visitEnd();
        invoker.visitEnd();
        writer.visitEnd();
        Path input = temp.resolve("in.jar");
        Path output = temp.resolve("out.jar");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("x/I.class", writer.toByteArray());
        writeJar(input, files);

        ModJarTranslator.translate(TestMappings.translation(), input, output);

        ClassNode node = new ClassNode();
        new ClassReader(read(output, "x/I.class")).accept(node, 0);
        AnnotationNode annotation = node.methods.getFirst().visibleAnnotations.getFirst();
        // method_20 is tick in class_1 and run in class_2; the Mixin targets class_2.
        assertEquals(java.util.List.of("value", "run"), annotation.values);
    }

    @Test
    void aClassTweakerInAnotherNamespaceIsNotTouched() {
        String text = "accessWidener\tv1\tofficial\naccessible\tclass\tnet/minecraft/class_1\n";
        assertTrue(ClassTweakerTranslator.translate(text, TestMappings.translation())
                .isEmpty());
        assertTrue(ClassTweakerTranslator.translate("not a header\n", TestMappings.translation())
                .isEmpty());
    }

    @Test
    void windowsLineEndingsSurvive() {
        String text = "classTweaker\tv1\tintermediary\r\naccessible\tclass\tnet/minecraft/class_1\r\n";
        assertEquals(
                "classTweaker\tv1\tofficial\r\naccessible\tclass\tnet/minecraft/world/Level\r\n",
                ClassTweakerTranslator.translate(text, TestMappings.translation())
                        .orElseThrow());
    }
}
