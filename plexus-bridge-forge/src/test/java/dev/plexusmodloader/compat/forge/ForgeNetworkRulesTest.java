package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import dev.plexusmodloader.internal.transform.engine.ClassTransformExecutor;
import dev.plexusmodloader.internal.transform.engine.ClassTransformPlanner;
import dev.plexusmodloader.internal.transform.engine.TransformPlan;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * Forge on NeoForge, F-N6 networking (ADR-0051 decision 22): {@link ForgeNetworkRules} against the {@code universal} jars
 * of the Forge releases Plexus Mod Loader pins for Minecraft 26.1, 26.1.1, 26.1.2, 26.2 and 26.3 ({@code
 * plexus.forgeEventJars}): the pre-launch check finds one call per rule, the engine plans all three rules, and the
 * rewritten classes call the game layer's hooks with the code length unchanged.
 */
class ForgeNetworkRulesTest {

    private static final String GAME_LAYER = "dev/plexusmodloader/game/layer/v26_2";

    static Stream<Path> forgeUniversals() {
        String jars = System.getProperty("plexus.forgeEventJars");
        List<Path> found = Arrays.stream(jars.split(File.pathSeparator))
                .map(Path::of)
                .filter(jar -> jar.getFileName().toString().startsWith("forge-"))
                .toList();
        assertEquals(5, found.size(), jars);
        return found.stream();
    }

    @ParameterizedTest
    @MethodSource("forgeUniversals")
    void everyRuleFitsAndRewritesOneCallOfTheSameLength(Path universal) throws IOException {
        assertEquals(List.of(), ForgeNetworkRules.missing(universal));
        List<TransformRuleDefinition> rules = ForgeNetworkRules.rules("26.2", GAME_LAYER);
        try (JarFile jar = new JarFile(universal.toFile())) {
            TransformPlan plan =
                    new ClassTransformPlanner().plan(rules, "26.2", GameSide.CLIENT, name -> read(jar, name));
            assertEquals(7, plan.acceptedRuleCount(), plan.rejected().toString());

            ClassTransformExecutor executor = new ClassTransformExecutor(plan, Optional.empty(), entry -> {});
            for (ForgeNetworkRules.Redirect redirect : ForgeNetworkRules.REDIRECTS) {
                String owner = redirect.method().ownerInternalName();
                byte[] original = read(jar, owner).orElseThrow();
                byte[] transformed = executor.transform(owner, original);
                new ClassReader(transformed).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);

                MethodNode before = method(original, redirect);
                MethodNode after = method(transformed, redirect);
                assertEquals(
                        List.of(redirect.invoked().ownerInternalName() + "."
                                + redirect.invoked().name()),
                        calls(
                                before,
                                redirect.invoked().ownerInternalName(),
                                redirect.invoked().name()));
                assertEquals(
                        List.of(),
                        calls(
                                after,
                                redirect.invoked().ownerInternalName(),
                                redirect.invoked().name()));
                assertEquals(
                        List.of(GAME_LAYER + "/" + redirect.hooks() + "." + redirect.hook()),
                        calls(after, GAME_LAYER + "/" + redirect.hooks(), redirect.hook()));
                assertEquals(codeLength(original, redirect), codeLength(transformed, redirect), redirect::ruleId);
            }
        }
    }

    @Test
    void aJarWithoutForgesNetworkClassesFailsTheCheck() throws IOException {
        // NeoForge's universal jar is pinned next to Forge's and has none of Forge's network classes.
        Path neoForge = Arrays.stream(
                        System.getProperty("plexus.forgeEventJars").split(File.pathSeparator))
                .map(Path::of)
                .filter(jar -> jar.getFileName().toString().startsWith("neoforge-"))
                .findFirst()
                .orElseThrow();
        List<String> missing = ForgeNetworkRules.missing(neoForge);
        assertEquals(7, missing.size(), missing::toString);
        missing.forEach(line -> assertTrue(line.contains("has no method"), line));
    }

    @Test
    void theRulesTargetForgesClassesOnBothSides() {
        for (TransformRuleDefinition rule : ForgeNetworkRules.rules("26.2", GAME_LAYER)) {
            assertTrue(rule.sides().contains(GameSide.CLIENT));
            // Only the client's send to the server is a client-only rule.
            assertEquals(
                    !rule.ruleId().equals("plexus:forge_distributor_server_channel"),
                    rule.sides().contains(GameSide.DEDICATED_SERVER),
                    rule::ruleId);
            assertFalse(rule.target().ownerInternalName().startsWith("net/minecraft/"), rule::ruleId);
        }
    }

    private static MethodNode method(byte[] bytes, ForgeNetworkRules.Redirect redirect) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream()
                .filter(m -> m.name.equals(redirect.method().name())
                        && m.desc.equals(redirect.method().descriptor()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> calls(MethodNode method, String owner, String name) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                calls.add(call.owner + "." + call.name);
                if (owner.startsWith(GAME_LAYER)) {
                    assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
                }
            }
        }
        return calls;
    }

    private static int codeLength(byte[] bytes, ForgeNetworkRules.Redirect redirect) {
        ClassModel model = ClassFile.of().parse(bytes);
        return model.methods().stream()
                .filter(method -> method.methodName()
                                .equalsString(redirect.method().name())
                        && method.methodType().equalsString(redirect.method().descriptor()))
                .findFirst()
                .orElseThrow()
                .findAttribute(Attributes.code())
                .map(CodeAttribute::codeLength)
                .orElseThrow();
    }

    private static Optional<byte[]> read(JarFile jar, String internalName) {
        ZipEntry entry = jar.getEntry(internalName + ".class");
        if (entry == null) {
            return Optional.empty();
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return Optional.of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
