package dev.plexusmodloader.compat.forge.modlauncher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;
import net.minecraftforge.coremod.transformer.CoreModClassTransformer;
import org.junit.jupiter.api.Test;

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
}
