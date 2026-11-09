package dev.plexusmodloader.compat.fabric.mixin;

import dev.plexusmodloader.compat.bridge.HostedLaunch;
import dev.plexusmodloader.compat.bridge.InjectionPointReplacement;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves mixins' injection points at the calls Fabric Loader's and Quilt Loader's game patch adds where they run mods'
 * entrypoints ({@code Hooks.startClient} in the client's constructor, {@code Hooks.startServer} in the dedicated server's
 * {@code main}) to the call Plexus Mod Loader's version adapter makes there ({@link
 * InjectionPointReplacement.Placement#LOADER_HOOK}). The game Plexus Mod Loader runs has no loader call there, so an
 * injection such as owo-lib's {@code MinecraftClientMixin}, which freezes owo's registries right after the client
 * entrypoints ran ({@code @Group clientFreezeHooks}, one Fabric and one Quilt variant, exactly one must apply), found
 * no target and stopped the game.
 *
 * <p>Only annotation string values equal to a loader call are rewritten, in classes whose bytes name one. When a class
 * names both loaders' call, only Fabric Loader's moves: the bridge runs the Fabric initializer phases, and a group that
 * wants exactly one of them stays satisfied.
 *
 * <p>Thread: immutable; any thread.
 */
final class LoaderHookCalls {

    static final LoaderHookCalls NONE = new LoaderHookCalls(Map.of());

    private static final byte[] MARKER = "/impl/game/minecraft/Hooks;start".getBytes(StandardCharsets.UTF_8);
    private static final String FABRIC = "Lnet/fabricmc/loader/";
    private static final String QUILT = "Lorg/quiltmc/loader/";

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    /** Loader call (Mixin target notation) to the adapter's call at its place. */
    private final Map<String, String> replacements;

    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    private LoaderHookCalls(Map<String, String> replacements) {
        this.replacements = Map.copyOf(replacements);
    }

    /** The rules {@code host} gives ({@link InjectionPointReplacement.Placement#LOADER_HOOK}), or none. */
    static LoaderHookCalls of(HostedLaunch host) {
        Map<String, String> replacements = new LinkedHashMap<>();
        for (InjectionPointReplacement rule : host.injectionPointReplacements()) {
            if (rule.placement() == InjectionPointReplacement.Placement.LOADER_HOOK) {
                replacements.put(rule.replaced(), rule.replacement());
            }
        }
        return replacements.isEmpty() ? NONE : new LoaderHookCalls(replacements);
    }

    boolean isEmpty() {
        return replacements.isEmpty();
    }

    /**
     * {@code bytes} with each moved loader call, or the same array when the class names none. A class ASM cannot read
     * is left as it is. The first time a class changes, the log names it and the calls.
     */
    byte[] apply(byte[] bytes) {
        Optional<Rewritten> rewritten = rewrite(bytes);
        if (rewritten.isEmpty()) {
            return bytes;
        }
        if (logged.add(rewritten.get().className())) {
            LOGGER.info(
                    "[Plexus Mod Loader] {}: injections at {} move to Plexus Mod Loader's mod initialization call there",
                    rewritten.get().className(),
                    rewritten.get().calls());
        }
        return rewritten.get().bytes();
    }

    private record Rewritten(byte[] bytes, String className, Set<String> calls) {}

    private Optional<Rewritten> rewrite(byte[] bytes) {
        if (replacements.isEmpty() || indexOf(bytes, MARKER) < 0) {
            return Optional.empty();
        }
        ClassReader reader;
        try {
            reader = new ClassReader(bytes);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Set<String> named = new TreeSet<>();
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String call : replacements.keySet()) {
            if (text.contains(call)) {
                named.add(call);
            }
        }
        Map<String, String> moving = new LinkedHashMap<>();
        for (String call : named) {
            boolean quiltWithFabric = call.startsWith(QUILT) && named.contains(FABRIC + call.substring(QUILT.length()));
            if (!quiltWithFabric) {
                moving.put(call, replacements.get(call));
            }
        }
        if (moving.isEmpty()) {
            return Optional.empty();
        }
        Set<String> moved = new TreeSet<>();
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new Retargeting(writer, moving, moved), 0);
        if (moved.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Rewritten(writer.toByteArray(), reader.getClassName(), moved));
    }

    private static int indexOf(byte[] bytes, byte[] part) {
        outer:
        for (int i = 0; i <= bytes.length - part.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (bytes[i + j] != part[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Replaces matching string values in every annotation of the class, its fields and methods. */
    private static final class Retargeting extends ClassVisitor {

        private final Map<String, String> moving;
        private final Set<String> moved;

        Retargeting(ClassVisitor next, Map<String, String> moving, Set<String> moved) {
            super(Opcodes.ASM9, next);
            this.moving = moving;
            this.moved = moved;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return values(super.visitAnnotation(descriptor, visible));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            FieldVisitor field = super.visitField(access, name, descriptor, signature, value);
            return new FieldVisitor(Opcodes.ASM9, field) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    return values(super.visitAnnotation(annotation, visible));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, method) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    return values(super.visitAnnotation(annotation, visible));
                }
            };
        }

        private AnnotationVisitor values(AnnotationVisitor next) {
            return new AnnotationVisitor(Opcodes.ASM9, next) {
                @Override
                public void visit(String name, Object value) {
                    if (value instanceof String text && moving.containsKey(text)) {
                        moved.add(text);
                        super.visit(name, moving.get(text));
                        return;
                    }
                    super.visit(name, value);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    return values(super.visitAnnotation(name, descriptor));
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return values(super.visitArray(name));
                }
            };
        }
    }
}
