package dev.plexusmodloader.compat.forge.modlauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraftforge.coremod.transformer.CoreModClassTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/**
 * Which ModLauncher transformers see instance fields private: Forge 47/49's coremod {@code field_to_method.js}
 * redirects refuse fields Fabric class tweakers widened.
 */
class ForgeClassTransformerTest {

    @Test
    void forgesFieldToMethodCoremodFunctionsRedirectFields() {
        for (String function : Set.of("biome", "structure", "potion", "itemstack", "stairsblock")) {
            assertTrue(ForgeClassTransformer.redirectsFieldsToMethods(new CoreModClassTransformer("forge", function)));
        }
    }

    @Test
    void neoForgesFieldToGetterTransformersRedirectFields() {
        // NeoForge 21.1's Java coremod, and NeoForge 20.4's field_to_method.js functions owned by "neoforge".
        assertTrue(ForgeClassTransformer.redirectsFieldsToMethods(
                new net.neoforged.neoforge.coremods.ReplaceFieldWithGetterAccess()));
        for (String function : Set.of("biome", "structure", "flowing_fluid_block", "bucketitem", "stairsblock")) {
            assertTrue(ForgeClassTransformer.redirectsFieldsToMethods(
                    new net.neoforged.coremod.transformer.CoreModClassTransformer("neoforge", function)));
        }
        assertFalse(ForgeClassTransformer.redirectsFieldsToMethods(
                new net.neoforged.coremod.transformer.CoreModClassTransformer("neoforge", "method_redirector")));
        assertFalse(ForgeClassTransformer.redirectsFieldsToMethods(
                new net.neoforged.coremod.transformer.CoreModClassTransformer("forge", "biome")));
    }

    @Test
    void otherCoremodsAndOtherTransformersDoNot() {
        // Another of Forge's coremods, a mod's coremod with the same function name, and labels of another shape.
        assertFalse(ForgeClassTransformer.redirectsFieldsToMethods(
                new CoreModClassTransformer("forge", "getPosition_bouncer")));
        assertFalse(ForgeClassTransformer.redirectsFieldsToMethods(new CoreModClassTransformer("somemod", "biome")));
        assertFalse(ForgeClassTransformer.redirectsFieldsToMethods(new CoreModClassTransformer("biome")));
        assertFalse(ForgeClassTransformer.redirectsFieldsToMethods(new ITransformer<Object>() {
            @Override
            public Object transform(Object input, ITransformerVotingContext context) {
                return input;
            }

            @Override
            public TransformerVoteResult castVote(ITransformerVotingContext context) {
                return TransformerVoteResult.YES;
            }

            @Override
            public Set<Target> targets() {
                return Set.of();
            }

            @Override
            public String[] labels() {
                return new String[] {"forge", "biome"};
            }
        }));
    }

    /** Mixin reads its class metadata with the access an access transformer gave, and is not offered the class itself. */
    @Test
    void buildsTheClassAPluginReadsWithTheOtherPluginsApplied() {
        List<String> offered = new ArrayList<>();
        ILaunchPluginService accessTransformer = plugin("accesstransformer", offered, (node, reason) -> {
            node.methods.forEach(method -> method.access = Opcodes.ACC_PUBLIC);
            return ILaunchPluginService.ComputeFlags.SIMPLE_REWRITE;
        });
        ILaunchPluginService mixin = plugin("mixin", offered, (node, reason) -> {
            node.methods.clear();
            return ILaunchPluginService.ComputeFlags.SIMPLE_REWRITE;
        });
        ForgeClassTransformer transformer = new ForgeClassTransformer(
                () -> List.of(accessTransformer, mixin), new dev.plexusmodloader.compat.bridge.GameClassBytes() {
                    @Override
                    public Optional<byte[]> classBytes(String className) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean isDefined(String className) {
                        return false;
                    }
                });
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "game/LevelRenderer", null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PRIVATE, "renderHitOutline", "()V", null, null)
                .visitEnd();
        writer.visitEnd();

        ClassNode read = new ClassNode();
        new ClassReader(transformer.transformFor("mixin", "game.LevelRenderer", writer.toByteArray())).accept(read, 0);

        assertEquals(1, read.methods.size());
        assertEquals(Opcodes.ACC_PUBLIC, read.methods.get(0).access);
        assertEquals(List.of("accesstransformer:mixin"), offered);
    }

    private interface Processing {
        int process(ClassNode node, String reason);
    }

    /** A launch plugin that, as Mixin's, skips classes offered under its own name. */
    private static ILaunchPluginService plugin(String name, List<String> offered, Processing processing) {
        return new ILaunchPluginService() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
                return EnumSet.of(Phase.BEFORE);
            }

            @Override
            public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty, String reason) {
                return name.equals(reason) ? EnumSet.noneOf(Phase.class) : EnumSet.of(Phase.BEFORE);
            }

            @Override
            public int processClassWithFlags(Phase phase, ClassNode classNode, Type classType, String reason) {
                offered.add(name + ":" + reason);
                return processing.process(classNode, reason);
            }
        };
    }
}
