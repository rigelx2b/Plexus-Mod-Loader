package dev.plexusmodloader.game.layer.v26_2.content;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;

/**
 * Makes mod blocks flammable (ADR-0040). The fire block keeps its odds in two private maps, filled by its own
 * bootstrap and read when fire spreads; mod blocks are added to the same maps. The fields have the same
 * name and type in every supported version (javap).
 */
final class Flammability {

    private static final MethodHandle IGNITE_ODDS;
    private static final MethodHandle BURN_ODDS;

    static {
        try {
            MethodHandles.Lookup fire = MethodHandles.privateLookupIn(FireBlock.class, MethodHandles.lookup());
            IGNITE_ODDS = fire.findGetter(FireBlock.class, "igniteOdds", Object2IntMap.class);
            BURN_ODDS = fire.findGetter(FireBlock.class, "burnOdds", Object2IntMap.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Flammability() {}

    /** Registers {@code block} with the odds fire uses for planks (5, 20), wool (30, 60), and so on. */
    @SuppressWarnings("unchecked")
    static void add(Block block, int igniteOdds, int burnOdds) {
        try {
            ((Object2IntMap<Block>) (Object2IntMap<?>) IGNITE_ODDS.invoke((FireBlock) Blocks.FIRE))
                    .put(block, igniteOdds);
            ((Object2IntMap<Block>) (Object2IntMap<?>) BURN_ODDS.invoke((FireBlock) Blocks.FIRE)).put(block, burnOdds);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Reading the fire block's odds failed", e);
        }
    }
}
