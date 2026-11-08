package net.neoforged.neoforge.coremods;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;

/** Test stand-in with the class name of NeoForge 21.1's coremod that redirects private field reads to getters. */
public final class ReplaceFieldWithGetterAccess implements ITransformer<Object> {

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
        return new String[] {"neoforge", "replace_field_with_getter_access"};
    }
}
