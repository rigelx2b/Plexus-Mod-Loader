package dev.plexusmodloader.compat.forge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Renames the SRG fields and methods of Minecraft Forge 1.20.x's files to Mojang's names ({@link SrgMojangNames},
 * ADR-0038 point 5): in class files (member declarations and references, method handles, {@code invokedynamic} names,
 * string constants that are exactly one SRG name such as the ones given to {@code ObfuscationReflectionHelper}, and
 * annotation strings that contain one, such as Mixin's {@code method} and {@code target}); in access transformers and
 * Mixin reference maps; and in jars nested under {@code META-INF/} ({@code META-INF/jarjar/}, {@code META-INF/jars/}). Jar signatures are dropped because the content
 * changes.
 *
 * <p>The member is identified by its name alone (an SRG id is unique), so no class hierarchy is read. Classes without an
 * SRG name in them are copied as they are.
 *
 * <p>Thread: stateless; every call is independent.
 */
final class SrgTranslator {

    private static final int MAX_NESTING = 4;
    // The element of Mixin's injector annotations (@Inject, @Redirect, @ModifyVariable, ...) that names target methods.
    private static final String METHOD_SELECTOR = "method";
    /**
     * Changes when the translation changes (2: jars nested anywhere under META-INF, such as META-INF/jars/; 3: a Mixin
     * {@code method} that is one SRG name of an overloaded method gets the Mojang descriptor; 4: not when overrides give the
     * SRG id other descriptors; 5: also when the selector names the owner, "Lowner;m_1_").
     */
    static final int FORMAT = 5;

    // JarJar puts nested jars where its metadata.json says: META-INF/jarjar/ or META-INF/jars/ in practice.
    private static final String NESTED_JARS = "meta-inf/";

    private final SrgMojangNames names;

    SrgTranslator(SrgMojangNames names) {
        this.names = names;
    }

    /** Whether the class file bytes contain something that looks like an SRG name; a cheap pre-check. */
    static boolean mayContainSrgName(byte[] bytes) {
        for (int i = 0; i + 3 < bytes.length; i++) {
            byte first = bytes[i];
            if ((first == 'm' || first == 'f') && bytes[i + 1] == '_' && bytes[i + 2] >= '0' && bytes[i + 2] <= '9') {
                return true;
            }
        }
        return false;
    }

    /**
     * The class with its SRG members renamed.
     *
     * @return {@code bytes} itself when the class has no SRG name
     */
    byte[] translateClass(byte[] bytes) {
        if (!mayContainSrgName(bytes)) {
            return bytes;
        }
        ClassReader reader = new ClassReader(bytes);
        // No frames are recomputed: renaming members does not change a method's code shape.
        ClassWriter writer = new ClassWriter(0);
        Remapper remapper = new SrgRemapper();
        reader.accept(new ClassRemapper(new StringTranslator(Opcodes.ASM9, writer), remapper), 0);
        byte[] result = writer.toByteArray();
        return java.util.Arrays.equals(result, bytes) ? bytes : result;
    }

    /**
     * Writes {@code input} to {@code output} with SRG names renamed everywhere in it, unless there is no SRG name to
     * rename. {@code output} is written to a partial file and moved, so a failed run leaves none.
     *
     * @return whether {@code output} was written; {@code false} when {@code input} has nothing to rename (use it as it
     *     is)
     * @throws IOException when a jar cannot be read or written
     */
    boolean translateJar(Path input, Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path partial = output.resolveSibling(output.getFileName() + ".partial");
        boolean changed;
        try (OutputStream out = Files.newOutputStream(partial)) {
            changed = translateJar(input, out, 0);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        if (!changed) {
            Files.deleteIfExists(partial);
            return false;
        }
        Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /** @return whether a class, access transformer, reference map or nested jar changed */
    private boolean translateJar(Path jar, OutputStream target, int depth) throws IOException {
        boolean changed = false;
        try (JarFile source = new JarFile(jar.toFile());
                JarOutputStream out = new JarOutputStream(target)) {
            Set<String> written = new HashSet<>();
            Manifest manifest = source.getManifest();
            if (manifest != null) {
                Manifest unsigned = unsigned(manifest);
                out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                unsigned.write(out);
                out.closeEntry();
                written.add("META-INF/MANIFEST.MF");
            }
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!written.add(name) || isSignature(name)) {
                    continue;
                }
                JarEntry copy = new JarEntry(name);
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    try (InputStream in = source.getInputStream(entry)) {
                        byte[] content = in.readAllBytes();
                        byte[] translated = translateEntry(name, content, depth);
                        changed |= translated != content;
                        out.write(translated);
                    }
                }
                out.closeEntry();
            }
        }
        return changed;
    }

    /** @return {@code content} itself when nothing in it changed */
    private byte[] translateEntry(String name, byte[] content, int depth) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".class")) {
            return translateClass(content);
        }
        if (lower.startsWith(NESTED_JARS) && lower.endsWith(".jar") && depth < MAX_NESTING) {
            // A JarFile needs a file; the nested jars are small.
            Path temp = Files.createTempFile("plexus-srg-", ".jar");
            try {
                Files.write(temp, content);
                ByteArrayOutputStream nested = new ByteArrayOutputStream(content.length);
                return translateJar(temp, nested, depth + 1) ? nested.toByteArray() : content;
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        if (isTranslatedText(lower)) {
            String text = new String(content, StandardCharsets.UTF_8);
            String translated = names.translateText(text);
            return translated.equals(text) ? content : translated.getBytes(StandardCharsets.UTF_8);
        }
        return content;
    }
    /**
     * The manifest without the digests of the entries, which no longer match the content. The main attributes and the
     * per-package ones such as {@code Implementation-Version} (Forge reads its own version from them) stay.
     */
    static Manifest unsigned(Manifest manifest) {
        // Copied attribute by attribute: Manifest(Manifest) shares the entries' Attributes, and the jar being read is
        // verified against this very manifest.
        Manifest result = new Manifest();
        result.getMainAttributes().putAll(manifest.getMainAttributes());
        manifest.getEntries().forEach((section, attributes) -> {
            Attributes kept = new Attributes();
            attributes.forEach((key, value) -> {
                String name = key.toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith("-digest") && !name.equals("magic")) {
                    kept.put(key, value);
                }
            });
            if (!kept.isEmpty()) {
                result.getEntries().put(section, kept);
            }
        });
        return result;
    }

    /** Access transformers, coremods and Mixin reference maps name members by their SRG name. */
    static boolean isTranslatedText(String lowerCaseName) {
        // Coremods are JavaScript that names the members they patch.
        if (lowerCaseName.endsWith(".cfg") || lowerCaseName.endsWith(".js")) {
            return true;
        }
        return lowerCaseName.endsWith(".json") && lowerCaseName.contains("refmap");
    }

    private static boolean isSignature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/")
                && upper.indexOf('/', "META-INF/".length()) < 0
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }

    /** Maps member names by SRG id; owners and descriptors are not needed. */
    private final class SrgRemapper extends Remapper {

        SrgRemapper() {
            super(Opcodes.ASM9);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return names.translate(name);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return names.translate(name);
        }

        @Override
        public String mapInvokeDynamicMethodName(
                String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            return names.translate(name);
        }

        @Override
        public String mapRecordComponentName(String owner, String name, String descriptor) {
            return names.translate(name);
        }
    }

    /** Renames SRG names in string constants and annotation values, which {@link Remapper} leaves alone. */
    private final class StringTranslator extends ClassVisitor {

        StringTranslator(int api, ClassVisitor next) {
            super(api, next);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotations(super.visitAnnotation(descriptor, visible));
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
                int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
            return annotations(super.visitTypeAnnotation(typeRef, typePath, descriptor, visible));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            Object constant =
                    value instanceof String text && SrgMojangNames.isSrgName(text) ? names.translate(text) : value;
            FieldVisitor next = super.visitField(access, name, descriptor, signature, constant);
            return next == null
                    ? null
                    : new FieldVisitor(api, next) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            return annotations(super.visitAnnotation(annotation, visible));
                        }
                    };
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            return next == null
                    ? null
                    : new MethodVisitor(api, next) {
                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof String text && SrgMojangNames.isSrgName(text)) {
                                super.visitLdcInsn(names.translate(text));
                            } else {
                                super.visitLdcInsn(value);
                            }
                        }

                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            return annotations(super.visitAnnotation(annotation, visible));
                        }

                        @Override
                        public AnnotationVisitor visitAnnotationDefault() {
                            return annotations(super.visitAnnotationDefault());
                        }

                        @Override
                        public AnnotationVisitor visitParameterAnnotation(
                                int parameter, String annotation, boolean visible) {
                            return annotations(super.visitParameterAnnotation(parameter, annotation, visible));
                        }

                        @Override
                        public AnnotationVisitor visitInsnAnnotation(
                                int typeRef, org.objectweb.asm.TypePath typePath, String annotation, boolean visible) {
                            return annotations(super.visitInsnAnnotation(typeRef, typePath, annotation, visible));
                        }
                    };
        }

        private AnnotationVisitor annotations(AnnotationVisitor next) {
            return annotations(next, false);
        }

        /** @param selectors whether the values are Mixin method selectors (the elements of a {@code method} array) */
        private AnnotationVisitor annotations(AnnotationVisitor next, boolean selectors) {
            if (next == null) {
                return null;
            }
            return new AnnotationVisitor(api, next) {
                @Override
                public void visit(String name, Object value) {
                    super.visit(name, value instanceof String text ? translate(name, text) : value);
                }

                private String translate(String name, String text) {
                    boolean selector = selectors || METHOD_SELECTOR.equals(name);
                    if (selector && text.startsWith("m_") && SrgMojangNames.isSrgName(text)) {
                        return names.methodSelector(text);
                    }
                    // An owner-qualified selector, "Lowner;m_1_": the same, after the owner.
                    int ownerEnd = text.startsWith("L") ? text.indexOf(';') + 1 : 0;
                    if (selector && ownerEnd > 0 && SrgMojangNames.isSrgName(text.substring(ownerEnd))) {
                        return text.substring(0, ownerEnd) + names.methodSelector(text.substring(ownerEnd));
                    }
                    return names.translateText(text);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    return annotations(super.visitAnnotation(name, descriptor));
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return annotations(super.visitArray(name), METHOD_SELECTOR.equals(name));
                }
            };
        }
    }
}
