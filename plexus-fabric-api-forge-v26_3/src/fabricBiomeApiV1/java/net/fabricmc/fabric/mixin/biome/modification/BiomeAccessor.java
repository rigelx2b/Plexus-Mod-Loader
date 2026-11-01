package net.fabricmc.fabric.mixin.biome.modification;

import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.common.world.ModifiableBiomeInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

// Plexus Mod Loader (ADR-0031): the ModifiableBiomeInfo field MinecraftForge 26.3-66.1.0 adds to Biome and the method
// its info calls back with the modified result (javap), for ForgeBiomeInfo.
@Mixin(Biome.class)
public interface BiomeAccessor {
    @Mutable
    @Accessor("modifiableBiomeInfo")
    void fabric_setModifiableBiomeInfo(ModifiableBiomeInfo info);

    @Invoker("update")
    void fabric_update(ModifiableBiomeInfo.BiomeInfo info);
}
