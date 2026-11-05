package dev.plexusmodloader.game.layer.v1_21_11;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.dimension.LevelStem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code WorldDimensions.bake} before 26.1 calls a world stable only with exactly the built-in dimensions, so a mod's
 * dimension made every world ask for a backup when it opened. The hook answers as 26.x asks: are the built-in ones there?
 */
class BakedDimensionCountTest {

    private static final int BUILT_IN = 3;

    /** The shape of the game's local record in {@code bake}: only its {@code key()} is read. */
    private record Entry(ResourceKey<LevelStem> key) {}

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Entry mod() {
        return new Entry(ResourceKey.create(Registries.LEVEL_STEM, Identifier.fromNamespaceAndPath("mod", "pocket")));
    }

    private static List<Entry> builtIn() {
        return List.of(new Entry(LevelStem.OVERWORLD), new Entry(LevelStem.NETHER), new Entry(LevelStem.END));
    }

    @Test
    void theBuiltInDimensionsCountAsTheyAre() {
        assertEquals(BUILT_IN, GameHooks.onBakedDimensionCount(builtIn()));
    }

    @Test
    void aModDimensionDoesNotChangeTheCount() {
        List<Entry> entries = new java.util.ArrayList<>(builtIn());
        entries.add(mod());
        assertEquals(BUILT_IN, GameHooks.onBakedDimensionCount(entries));
    }

    @Test
    void aMissingBuiltInDimensionCannotMatchEvenWithTheSameCount() {
        List<Entry> entries = List.of(new Entry(LevelStem.OVERWORLD), new Entry(LevelStem.NETHER), mod());
        assertEquals(-1, GameHooks.onBakedDimensionCount(entries));
    }
}
