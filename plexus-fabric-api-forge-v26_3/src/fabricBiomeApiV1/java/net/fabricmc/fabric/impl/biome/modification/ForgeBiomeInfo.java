package net.fabricmc.fabric.impl.biome.modification;

import net.fabricmc.fabric.mixin.biome.modification.BiomeAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.common.world.ModifiableBiomeInfo;

// Plexus Mod Loader (ADR-0031), for MinecraftForge 26.3-66.1.0 (javap): Forge's Biome reads its generation settings
// from a ModifiableBiomeInfo. At the server start Forge builds its modified info from a copy of the original and writes
// the climate, attributes, effects and generation settings of the result back into the biome's fields (Biome.update).
// Fabric API changes the biome before that (in the MinecraftServer constructor): the generation settings in place, the
// climate, attributes (spawns among them) and effects by replacing the biome's fields, which that write would undo. The
// biome gets a new ModifiableBiomeInfo whose original holds the biome as Fabric API left it, so that Forge's own
// modifiers start from it.
final class ForgeBiomeInfo {
    private ForgeBiomeInfo() {}

    static void keepModifications(Biome biome) {
        ModifiableBiomeInfo forgeInfo = biome.modifiableBiomeInfo();
        if (forgeInfo.getModifiedBiomeInfo() != null) {
            // Forge applies its modifiers once, after the MinecraftServer constructor where Fabric API runs.
            throw new IllegalStateException(
                    "Forge's biome modifiers already ran before Fabric API's biome modifications");
        }
        ModifiableBiomeInfo.BiomeInfo original = forgeInfo.getOriginalBiomeInfo();
        BiomeAccessor accessor = (BiomeAccessor) (Object) biome;
        accessor.fabric_setModifiableBiomeInfo(new ModifiableBiomeInfo(
                new ModifiableBiomeInfo.BiomeInfo(
                        biome.climateSettings,
                        biome.getAttributes(),
                        biome.getSpecialEffects(),
                        original.generationSettings()),
                accessor::fabric_update));
    }
}
