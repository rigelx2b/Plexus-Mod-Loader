// Source: Sinytra Forgified Fabric API, branch 26.2.x, commit 40f8ce0,
// fabric-model-loading-api-v1/src/client/java/net/fabricmc/fabric/mixin/client/model/loading/BlockStateModelHooksAccessor.java
// (written in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.client.model.loading;

import com.mojang.serialization.MapCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.neoforged.neoforge.client.model.block.BlockStateModelHooks;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockStateModelHooks.class)
public interface BlockStateModelHooksAccessor {
    @Accessor("BLOCK_STATE_MODEL_IDS")
    static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends CustomUnbakedBlockStateModel>>
            getBlockStateModelIDs() {
        throw new UnsupportedOperationException();
    }
}
