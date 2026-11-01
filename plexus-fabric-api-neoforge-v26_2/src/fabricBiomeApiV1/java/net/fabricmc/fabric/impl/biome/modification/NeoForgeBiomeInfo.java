package net.fabricmc.fabric.impl.biome.modification;

import net.fabricmc.fabric.mixin.biome.modification.BiomeAccessor;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

// Plexus Mod Loader (ADR-0031), for NeoForge 26.2.0.93 (javap): NeoForge's Biome reads its generation and spawn
// settings
// from a ModifiableBiomeInfo, and at the server start (ServerLifecycleHooks.runModifiers) NeoForge builds its modified
// info from a copy of that info's original. Fabric API changes the biome before that (in the MinecraftServer
// constructor): the generation and spawn settings and the effects in place, the climate by replacing the biome's field.
// The biome gets a new ModifiableBiomeInfo whose original holds the biome as Fabric API left it, so that NeoForge's own
// modifiers start from it, as MinecraftForge's do in Plexus Mod Loader's build for Forge (ForgeBiomeInfo there).
final class NeoForgeBiomeInfo {
    private NeoForgeBiomeInfo() {}

    static void keepModifications(Biome biome) {
        ModifiableBiomeInfo neoForgeInfo = biome.modifiableBiomeInfo();
        if (neoForgeInfo.getModifiedBiomeInfo() != null) {
            // NeoForge applies its modifiers once, after the MinecraftServer constructor where Fabric API runs.
            throw new IllegalStateException(
                    "NeoForge's biome modifiers already ran before Fabric API's biome modifications");
        }
        ModifiableBiomeInfo.BiomeInfo original = neoForgeInfo.getOriginalBiomeInfo();
        ((BiomeAccessor) (Object) biome)
                .fabric_setModifiableBiomeInfo(new ModifiableBiomeInfo(new ModifiableBiomeInfo.BiomeInfo(
                        biome.climateSettings,
                        biome.getSpecialEffects(),
                        original.generationSettings(),
                        original.mobSpawnSettings())));
    }
}
