package net.fabricmc.fabric.mixin.biome.modification;

import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.common.world.ModifiableBiomeInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// Plexus Mod Loader (ADR-0031): the ModifiableBiomeInfo field MinecraftForge 26.2-65.1.5 adds to Biome (javap), for
// ForgeBiomeInfo.
@Mixin(Biome.class)
public interface BiomeAccessor {
    @Mutable
    @Accessor("modifiableBiomeInfo")
    void fabric_setModifiableBiomeInfo(ModifiableBiomeInfo info);
}
