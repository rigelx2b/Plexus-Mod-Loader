package dev.plexusmodloader.game.layer.v1_20_4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.game.version.v1_20_4.Minecraft1_20_4Adapter;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import dev.plexusmodloader.internal.transform.engine.ClassBytesSource;
import dev.plexusmodloader.internal.transform.engine.ClassTransformExecutor;
import dev.plexusmodloader.internal.transform.engine.ClassTransformPlanner;
import dev.plexusmodloader.internal.transform.engine.TransformPlan;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Loads the classes the built-in rules transform into a class loader of their own and links them, so the JVM's
 * bytecode verifier checks the hand-written stack map frames and the inserted calls. This is the check that runs a
 * client rule's code without opening a window: the client classes are linked, not initialised.
 */
class TransformedClassesVerifyTest {

    @Test
    void transformedClientClassesPassTheBytecodeVerifier() throws ClassNotFoundException {
        assertEquals(List.of(), verify(GameSide.CLIENT), "client classes that failed to link");
    }

    @Test
    void transformedServerClassesPassTheBytecodeVerifier() throws ClassNotFoundException {
        assertEquals(List.of(), verify(GameSide.DEDICATED_SERVER), "server classes that failed to link");
    }

    private static List<String> verify(GameSide side) throws ClassNotFoundException {
        List<TransformRuleDefinition> rules = new Minecraft1_20_4Adapter().builtInTransformRules();
        ClassBytesSource source = TransformedClassesVerifyTest::read;
        TransformPlan plan = new ClassTransformPlanner().plan(rules, "1.20.4", side, source);
        assertEquals(List.of(), plan.rejected().stream().map(Object::toString).toList());
        ClassTransformExecutor executor = new ClassTransformExecutor(plan, Optional.empty(), entry -> {});
        Map<String, byte[]> transformed = new HashMap<>();
        for (String internalName : plan.rulesByClass().keySet()) {
            transformed.put(
                    internalName.replace('/', '.'),
                    executor.transform(internalName, read(internalName).orElseThrow()));
        }
        assertFalse(transformed.isEmpty());
        ClassLoader loader = new TransformedLoader(transformed, TransformedClassesVerifyTest.class.getClassLoader());
        List<String> failures = new ArrayList<>();
        for (String name : transformed.keySet()) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                // Reflection links the class, which verifies every method.
                type.getDeclaredMethods();
            } catch (LinkageError | RuntimeException e) {
                failures.add(name + ": " + e);
            }
        }
        if (side == GameSide.CLIENT) {
            // The client half's own classes, linked in the same loader (hooks, renderers, screens, models).
            for (String name : List.of(
                    "ClientHooks",
                    "ItemTooltips",
                    "CreativeTabPaging",
                    "ModShaderPipelines",
                    "WorldPainting",
                    "PlexusBlockEntityRenderer",
                    "PlexusEntityModel",
                    "PlexusModelRenderer",
                    "PlexusHumanoidRenderer",
                    "PlexusParticle",
                    "PlexusParticle$Provider",
                    "PlexusScreen",
                    "PlexusContainerScreen",
                    "NotLoadedModsScreen",
                    "DynamicTextures",
                    "TitleScreenModCounts")) {
                String full = "dev.plexusmodloader.game.layer.v1_20_4.client." + name;
                try {
                    Class.forName(full, false, loader).getDeclaredMethods();
                } catch (LinkageError | ClassNotFoundException | RuntimeException e) {
                    failures.add(full + ": " + e);
                }
            }
        }
        return failures;
    }

    /**
     * Defines the transformed classes, and every other game class and class of the game layer, itself: a class the
     * parent loaded would clash with the transformed copy of the same name (a loader constraint). Libraries come from
     * the parent.
     */
    private static final class TransformedLoader extends ClassLoader {

        private final Map<String, byte[]> transformed;

        TransformedLoader(Map<String, byte[]> transformed, ClassLoader parent) {
            super(parent);
            this.transformed = transformed;
        }

        private static boolean ownClass(String name) {
            return name.startsWith("net.minecraft.")
                    || name.startsWith("com.mojang.blaze3d.")
                    || name.startsWith("com.mojang.realmsclient.")
                    || name.startsWith("dev.plexusmodloader.game.layer.v1_20_4.")
                    || name.startsWith("dev.plexusmodloader.game.version.v1_20_4.");
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && ownClass(name)) {
                    byte[] bytes = transformed.get(name);
                    if (bytes == null) {
                        bytes = read(name.replace('.', '/')).orElse(null);
                    }
                    if (bytes != null) {
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    }
                }
                if (loaded != null) {
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }
    }

    private static Optional<byte[]> read(String internalName) {
        try (InputStream in =
                TransformedClassesVerifyTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
