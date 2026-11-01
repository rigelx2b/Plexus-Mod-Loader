package net.fabricmc.fabric.impl.biome.modification;

import net.fabricmc.fabric.mixin.biome.modification.BiomeAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.common.world.ModifiableBiomeInfo;

// Plexus Mod Loader (ADR-0031), for MinecraftForge 26.2-65.1.5 (javap): Forge's Biome reads its generation and spawn
// settings from a ModifiableBiomeInfo, and its codecs (DIRECT_CODEC, NETWORK_CODEC) write the climate and effects of
// that info's original. At the server start Forge builds its modified info from a copy of the original. Fabric API
// changes the biome before that (in the MinecraftServer constructor): the generation and spawn settings and the effects
// in place, the climate by replacing the biome's field, which a biome written and read again through those codecs, or
// sent to a client, loses (forgeFabricApiGameTest reads the vanilla temperature without this). The biome gets a new
// ModifiableBiomeInfo whose original holds the biome as Fabric API left it, so that Forge's own modifiers start from it
// and its codecs write Fabric API's climate.
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
        ((BiomeAccessor) (Object) biome)
                .fabric_setModifiableBiomeInfo(new ModifiableBiomeInfo(new ModifiableBiomeInfo.BiomeInfo(
                        biome.climateSettings,
                        biome.getSpecialEffects(),
                        original.generationSettings(),
                        original.mobSpawnSettings())));
    }
}
