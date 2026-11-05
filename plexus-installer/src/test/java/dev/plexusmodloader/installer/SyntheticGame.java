package dev.plexusmodloader.installer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A tiny obfuscated "game" for tests (made here, so no Minecraft code is in the repository): classes {@code a}
 * (Mojang name {@code net.minecraft.world.Thing}), {@code d extends a} ({@code Sub}) and {@code f extends a}
 * ({@code Other}), a field {@code a.b} ({@code count}), a method {@code a.c()} ({@code tick}) and {@code d.e()}
 * ({@code update}). {@code d.e()} reaches the inherited field and method through the subclass and merges two
 * subclasses in a stack map frame, which is what a remap has to get right.
 */
final class SyntheticGame {

    static final String MAPPINGS = """
            # (c) test mappings, made up for the tests
            net.minecraft.world.Thing -> a:
                int count -> b
                void tick() -> c
            net.minecraft.world.Sub -> d:
                void update() -> e
            net.minecraft.world.Other -> f:
            """;

    /** Mappings that name none of the classes above. */
    static final String UNRELATED_MAPPINGS = """
            net.minecraft.other.Else -> zz:
                int x -> y
            """;

    static final String VERSION_JSON = "{\"id\": \"test\"}";

    private SyntheticGame() {}

    /** The obfuscated jar: signed (as Mojang's are), with a {@code version.json} and a main class manifest. */
    static byte[] jar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            put(jar, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nMain-Class: net.minecraft.Main\n\n");
            put(jar, "META-INF/MOJANGCS.SF", "Signature-Version: 1.0\n");
            put(jar, "META-INF/MOJANGCS.RSA", "not really a signature");
            put(jar, "version.json", VERSION_JSON);
            put(jar, "a.class", base());
            put(jar, "d.class", sub());
            put(jar, "f.class", other());
        }
        return bytes.toByteArray();
    }

    static void write(Path file) throws IOException {
        Files.write(file, jar());
    }

    private static void put(JarOutputStream jar, String name, String content) throws IOException {
        put(jar, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(JarOutputStream jar, String name, byte[] content) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }

    private static ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return type1.equals(type2) ? type1 : "a";
            }
        };
    }

    private static void constructor(ClassWriter writer, String superName) {
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    private static byte[] base() {
        ClassWriter writer = writer();
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "a", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "b", "I", null, null).visitEnd();
        constructor(writer, "java/lang/Object");
        MethodVisitor tick = writer.visitMethod(Opcodes.ACC_PUBLIC, "c", "()V", null, null);
        tick.visitCode();
        tick.visitInsn(Opcodes.RETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] other() {
        ClassWriter writer = writer();
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "f", null, "a", null);
        constructor(writer, "a");
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * {@code d.e()}: {@code this.c(); this.b = 1; a x = this.b == 0 ? new d() : new f(); x.c();} through the
     * subclass {@code d}, with {@code x} merging {@code d} and {@code f} to {@code a}.
     */
    private static byte[] sub() {
        ClassWriter writer = writer();
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "d", null, "a", null);
        constructor(writer, "a");
        MethodVisitor update = writer.visitMethod(Opcodes.ACC_PUBLIC, "e", "()V", null, null);
        update.visitCode();
        update.visitVarInsn(Opcodes.ALOAD, 0);
        update.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "d", "c", "()V", false);
        update.visitVarInsn(Opcodes.ALOAD, 0);
        update.visitInsn(Opcodes.ICONST_1);
        update.visitFieldInsn(Opcodes.PUTFIELD, "d", "b", "I");
        Label other = new Label();
        Label join = new Label();
        update.visitVarInsn(Opcodes.ALOAD, 0);
        update.visitFieldInsn(Opcodes.GETFIELD, "d", "b", "I");
        update.visitJumpInsn(Opcodes.IFNE, other);
        update.visitTypeInsn(Opcodes.NEW, "d");
        update.visitInsn(Opcodes.DUP);
        update.visitMethodInsn(Opcodes.INVOKESPECIAL, "d", "<init>", "()V", false);
        update.visitVarInsn(Opcodes.ASTORE, 1);
        update.visitJumpInsn(Opcodes.GOTO, join);
        update.visitLabel(other);
        update.visitTypeInsn(Opcodes.NEW, "f");
        update.visitInsn(Opcodes.DUP);
        update.visitMethodInsn(Opcodes.INVOKESPECIAL, "f", "<init>", "()V", false);
        update.visitVarInsn(Opcodes.ASTORE, 1);
        update.visitLabel(join);
        update.visitVarInsn(Opcodes.ALOAD, 1);
        update.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "a", "c", "()V", false);
        update.visitInsn(Opcodes.RETURN);
        update.visitMaxs(0, 0);
        update.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
