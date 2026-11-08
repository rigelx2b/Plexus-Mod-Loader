package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class SrgTranslatorTest {

    private static final SrgMojangNames NAMES = SrgMojangNames.of(Map.of(
            "m_1_", "tick",
            "f_2_", "level",
            "m_3_", "compute",
            "f_4_", "data"));
    private final SrgTranslator translator = new SrgTranslator(NAMES);

    /** A class that declares f_2_ and m_1_, calls m_1_ on another class, reads its f_4_, and mentions SRG names. */
    private static byte[] sample() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Sample", null, "java/lang/Object", null);
        FieldVisitor field = writer.visitField(Opcodes.ACC_PUBLIC, "f_2_", "I", null, null);
        field.visitEnd();
        MethodVisitor tick = writer.visitMethod(Opcodes.ACC_PUBLIC, "m_1_", "()V", null, null);
        tick.visitCode();
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitFieldInsn(Opcodes.GETFIELD, "other/Level", "f_4_", "I");
        tick.visitInsn(Opcodes.POP);
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "other/Level", "m_3_", "()I", false);
        tick.visitInsn(Opcodes.POP);
        tick.visitLdcInsn("m_3_");
        tick.visitInsn(Opcodes.POP);
        tick.visitLdcInsn("not m_3_ alone");
        tick.visitInsn(Opcodes.POP);
        tick.visitInvokeDynamicInsn(
                "m_1_",
                "()Ljava/lang/Runnable;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                                + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false),
                Type.getMethodType("()V"),
                new Handle(Opcodes.H_INVOKEVIRTUAL, "mod/Sample", "m_1_", "()V", false),
                Type.getMethodType("()V"));
        tick.visitInsn(Opcodes.POP);
        tick.visitInsn(Opcodes.RETURN);
        tick.visitMaxs(2, 1);
        tick.visitEnd();
        var annotation = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        annotation.visit("method", "Lother/Level;m_1_()V");
        annotation.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private record Seen(Set<String> methods, Set<String> fields, Set<String> calls, Set<String> strings) {}

    private static Seen inspect(byte[] bytes) {
        Seen seen = new Seen(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public FieldVisitor visitField(
                                    int access, String name, String descriptor, String signature, Object value) {
                                seen.fields().add(name);
                                return null;
                            }

                            @Override
                            public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                    String descriptor, boolean visible) {
                                return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String name, Object value) {
                                        seen.strings().add("@" + value);
                                    }
                                };
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                seen.methods().add(name);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fieldName, String fieldDescriptor) {
                                        seen.calls().add("field " + fieldName);
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String methodName,
                                            String methodDescriptor,
                                            boolean itf) {
                                        seen.calls().add("method " + methodName);
                                    }

                                    @Override
                                    public void visitLdcInsn(Object value) {
                                        seen.strings().add(String.valueOf(value));
                                    }

                                    @Override
                                    public void visitInvokeDynamicInsn(
                                            String indyName, String indyDescriptor, Handle bsm, Object... args) {
                                        seen.calls().add("indy " + indyName);
                                        for (Object arg : args) {
                                            if (arg instanceof Handle handle) {
                                                seen.calls().add("handle " + handle.getName());
                                            }
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return seen;
    }

    @Test
    void renamesDeclarationsReferencesHandlesStringsAndAnnotations() {
        Seen seen = inspect(translator.translateClass(sample()));

        assertEquals(Set.of("tick"), seen.methods());
        assertEquals(Set.of("level"), seen.fields());
        assertTrue(seen.calls().containsAll(Set.of("field data", "method compute", "indy tick", "handle tick")));
        // A constant that is exactly an SRG name (reflection) is renamed; text around one is not.
        assertTrue(seen.strings().contains("compute"));
        assertTrue(seen.strings().contains("not m_3_ alone"));
        // Annotation values (Mixin selectors) are renamed inside the text.
        assertTrue(seen.strings().contains("@Lother/Level;tick()V"));
    }

    /** Entity Model Features on Forge 1.20.4: {@code @Inject(method = "m_104306_")}, one of two {@code render}s. */
    @Test
    void givesABareSelectorOfAnOverloadedMethodItsDescriptor() {
        String eight = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V";
        SrgMojangNames names = SrgMojangNames.of(
                Map.of("m_104306_", "render", "m_104301_", "render", "m_1_", "tick"),
                Map.of("m_104306_", eight, "m_104301_", "(Lcom/mojang/blaze3d/vertex/PoseStack;)V"));
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/MixinModelPart", null, "java/lang/Object", null);
        var single = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        single.visit("method", "m_104306_");
        single.visitEnd();
        var array = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Redirect;", false);
        var methods = array.visitArray("method");
        methods.visit(null, "m_104306_");
        methods.visit(null, "m_1_");
        // Owner-qualified, as Entity Texture Features writes for ParrotOnShoulderLayer.
        methods.visit(null, "Lnet/minecraft/client/model/geom/ModelPart;m_104306_");
        methods.visitEnd();
        // Not a selector: an @At target names the member with its descriptor already, an @Invoker a name.
        var at = array.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "m_104306_");
        at.visitEnd();
        array.visitEnd();
        writer.visitEnd();

        List<String> seen = new java.util.ArrayList<>();
        new ClassReader(new SrgTranslator(names).translateClass(writer.toByteArray()))
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                    String descriptor, boolean visible) {
                                return collect();
                            }

                            private org.objectweb.asm.AnnotationVisitor collect() {
                                return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String name, Object value) {
                                        seen.add(value.toString());
                                    }

                                    @Override
                                    public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
                                        return collect();
                                    }

                                    @Override
                                    public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                            String name, String descriptor) {
                                        return collect();
                                    }
                                };
                            }
                        },
                        0);

        assertEquals(
                List.of(
                        "render" + eight,
                        "render" + eight,
                        "tick",
                        "Lnet/minecraft/client/model/geom/ModelPart;render" + eight,
                        "render"),
                seen);
    }

    @Test
    void leavesAClassWithoutSrgNamesAsItIs() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Plain", null, "java/lang/Object", null);
        writer.visitEnd();
        byte[] plain = writer.toByteArray();

        assertSame(plain, translator.translateClass(plain));
    }

    @Test
    void translatesAJarKeepingPackageVersionsNestedJarsAndText(@TempDir Path directory) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        Attributes section = new Attributes();
        section.putValue("Implementation-Version", "49.2.13");
        section.putValue("SHA-256-Digest", "abc");
        manifest.getEntries().put("net/minecraftforge/versions/forge/", section);
        byte[] inner = jar(manifest, Map.of("mod/Sample.class", sample()));
        Path input = directory.resolve("in.jar");
        Files.write(
                input,
                jar(
                        manifest,
                        Map.of(
                                "mod/Sample.class", sample(),
                                "META-INF/accesstransformer.cfg",
                                        "public net.minecraft.Foo m_1_()V".getBytes(StandardCharsets.UTF_8),
                                "mod.refmap.json",
                                        "{\"mappings\":{\"X\":{\"a\":\"Lq;m_1_()V\"}}}"
                                                .getBytes(StandardCharsets.UTF_8),
                                "coremods/x.js", "methodName: 'm_3_'".getBytes(StandardCharsets.UTF_8),
                                "META-INF/FORGE.SF", "Signature-Version: 1.0".getBytes(StandardCharsets.UTF_8),
                                "META-INF/jarjar/inner.jar", inner,
                                // Where entityculling's JarJar metadata puts TRender (1.20.1 corpus).
                                "META-INF/jars/other.jar", inner)));
        Path output = directory.resolve("out.jar");

        assertTrue(translator.translateJar(input, output));

        try (JarFile jar = new JarFile(output.toFile())) {
            Attributes kept = jar.getManifest().getAttributes("net/minecraftforge/versions/forge/");
            assertEquals("49.2.13", kept.getValue("Implementation-Version"));
            assertNull(kept.getValue("SHA-256-Digest"));
            assertNull(jar.getEntry("META-INF/FORGE.SF"));
            assertEquals("public net.minecraft.Foo tick()V", text(jar, "META-INF/accesstransformer.cfg"));
            assertEquals("{\"mappings\":{\"X\":{\"a\":\"Lq;tick()V\"}}}", text(jar, "mod.refmap.json"));
            assertEquals("methodName: 'compute'", text(jar, "coremods/x.js"));
            assertEquals(Set.of("tick"), inspect(bytes(jar, "mod/Sample.class")).methods());
            for (String name : List.of("META-INF/jarjar/inner.jar", "META-INF/jars/other.jar")) {
                Path nested = directory.resolve(name.replace('/', '_'));
                Files.write(nested, bytes(jar, name));
                try (JarFile inside = new JarFile(nested.toFile())) {
                    assertEquals(
                            Set.of("tick"),
                            inspect(bytes(inside, "mod/Sample.class")).methods(),
                            name);
                }
            }
        }
    }

    @Test
    void writesNothingForAJarWithoutSrgNames(@TempDir Path directory) throws IOException {
        Path input = directory.resolve("plain.jar");
        Files.write(input, jar(new Manifest(), Map.of("readme.txt", "hello".getBytes(StandardCharsets.UTF_8))));
        Path output = directory.resolve("out").resolve("plain.jar");

        assertFalse(translator.translateJar(input, output));
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(output.resolveSibling("plain.jar.partial")));
    }

    @Test
    void recognisesWhatMayContainAnSrgName() {
        assertTrue(SrgTranslator.mayContainSrgName("xx m_123_ yy".getBytes(StandardCharsets.UTF_8)));
        assertFalse(SrgTranslator.mayContainSrgName("m_x f_ tick".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] jar(Manifest manifest, Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] bytes(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, name);
        try (InputStream in = new ByteArrayInputStream(jar.getInputStream(entry).readAllBytes())) {
            return in.readAllBytes();
        }
    }

    private static String text(JarFile jar, String name) throws IOException {
        return new String(bytes(jar, name), StandardCharsets.UTF_8);
    }
}
