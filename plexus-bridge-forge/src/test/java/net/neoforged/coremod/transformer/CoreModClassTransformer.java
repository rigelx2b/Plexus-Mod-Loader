package net.neoforged.coremod.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;

/** Test stand-in with the class name of NeoForge 20.4's coremod class transformer (coremods 6) and its labels. */
public final class CoreModClassTransformer implements ITransformer<Object> {

    private final String[] labels;

    public CoreModClassTransformer(String... labels) {
        this.labels = labels;
    }

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
        return labels;
    }
}
