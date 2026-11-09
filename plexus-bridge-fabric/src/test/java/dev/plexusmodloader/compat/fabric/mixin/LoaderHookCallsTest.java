package dev.plexusmodloader.compat.fabric.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.compat.bridge.HostedLaunch;
import dev.plexusmodloader.compat.bridge.InjectionPointReplacement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

class LoaderHookCallsTest {

    private static final String FABRIC =
            "Lnet/fabricmc/loader/impl/game/minecraft/Hooks;startClient(Ljava/io/File;Ljava/lang/Object;)V";
    private static final String QUILT =
            "Lorg/quiltmc/loader/impl/game/minecraft/Hooks;startClient(Ljava/io/File;Ljava/lang/Object;)V";
    private static final String PLEXUS =
            "Ldev/plexusmodloader/game/version/v1_20_1/Minecraft1_20_1Hooks;" + "onModInitialization()V";

    private static LoaderHookCalls calls() {
        List<InjectionPointReplacement> rules = new ArrayList<>();
        for (String loader : List.of(FABRIC, QUILT)) {
            rules.add(new InjectionPointReplacement(
                    "net/minecraft/client/Minecraft",
                    "<init>",
                    loader,
                    PLEXUS,
                    InjectionPointReplacement.Placement.LOADER_HOOK));
        }
        return LoaderHookCalls.of(new HostedLaunch("", List.of(), rules, null, false));
    }

    /** A mixin like owo-lib's MinecraftClientMixin: one handler per loader call, in one group. */
    private static byte[] mixin(List<String> targets) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "x/ClientMixin", null, "java/lang/Object", null);
        int index = 0;
        for (String target : targets) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, "after" + index++, "()V", null, null);
            AnnotationVisitor inject = method.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
            AnnotationVisitor ats = inject.visitArray("at");
            AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
            at.visit("value", "INVOKE");
            at.visit("target", target);
            at.visit("remap", false);
            at.visitEnd();
            ats.visitEnd();
            inject.visitEnd();
            method.visitCode();
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 1);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> targets(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> targets = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (method.visibleAnnotations == null) {
                continue;
            }
            for (AnnotationNode inject : method.visibleAnnotations) {
                @SuppressWarnings("unchecked")
                List<AnnotationNode> ats = (List<AnnotationNode>) inject.values.get(inject.values.indexOf("at") + 1);
                AnnotationNode at = ats.getFirst();
                targets.add((String) at.values.get(at.values.indexOf("target") + 1));
            }
        }
        return targets;
    }

    @Test
    void withBothLoadersCallsOnlyFabricLoadersMoves() {
        byte[] moved = calls().apply(mixin(List.of(FABRIC, QUILT)));

        assertEquals(List.of(PLEXUS, QUILT), targets(moved));
    }

    @Test
    void quiltLoadersCallAloneMoves() {
        assertEquals(List.of(PLEXUS), targets(calls().apply(mixin(List.of(QUILT)))));
    }

    @Test
    void aClassWithoutLoaderCallsIsLeftAsItIs() {
        byte[] bytes = mixin(List.of("Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"));

        assertSame(bytes, calls().apply(bytes));
    }

    @Test
    void withoutRulesNothingMoves() {
        byte[] bytes = mixin(List.of(FABRIC));

        assertSame(bytes, LoaderHookCalls.of(HostedLaunch.NONE).apply(bytes));
        assertTrue(LoaderHookCalls.of(HostedLaunch.NONE).isEmpty());
    }
}
