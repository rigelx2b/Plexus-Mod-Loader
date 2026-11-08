package dev.plexusmodloader.runtime.classloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.plexusmodloader.compat.bridge.BridgeClassTransformer;
import dev.plexusmodloader.internal.transform.engine.ClassTransformExecutor;
import dev.plexusmodloader.internal.transform.engine.TransformPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * A bridge's transformer makes classes no jar holds (Mixin's synthetic classes), but not a library class the parent
 * holds: FancyMenu's Mixin into LWJGL's {@code GLFW} made an empty {@code GLFW} in the game loader, which the {@code
 * minecraft} module could not read (Forge 1.20.1, NeoForge 21.1, 2026-11-08).
 */
class GameClassLoaderGeneratedTest {

    /** Generates every class it is asked for, as Mixin does for a class it has Mixins for. */
    private static final class GeneratingTransformer implements BridgeClassTransformer {

        final List<String> asked = new ArrayList<>();

        @Override
        public String name() {
            return "generating";
        }

        @Override
        public byte[] transform(String className, byte[] bytes) {
            return bytes;
        }

        @Override
        public byte[] generate(String className) {
            asked.add(className);
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className.replace('.', '/'), null, "java/lang/Object", null);
            writer.visitEnd();
            return writer.toByteArray();
        }
    }

    @Test
    void aLibraryClassOfTheParentIsNotGenerated() throws Exception {
        ClassTransformExecutor executor =
                new ClassTransformExecutor(new TransformPlan(Map.of(), List.of()), Optional.empty(), entry -> {});
        GeneratingTransformer generating = new GeneratingTransformer();
        try (GameClassLoader loader = new GameClassLoader(List.of(), getClass().getClassLoader(), executor)) {
            loader.installBridgeTransformer(generating);

            // A library on the parent's class path, as LWJGL is.
            assertSame(ClassWriter.class, loader.loadClass(ClassWriter.class.getName()));
            // No jar and no parent holds it: generated in the game loader.
            Class<?> synthetic = loader.loadClass("mod.mixin.Synthetic$Generated");
            assertSame(loader, synthetic.getClassLoader());
            assertEquals(List.of("mod.mixin.Synthetic$Generated"), generating.asked);
        }
    }
}
