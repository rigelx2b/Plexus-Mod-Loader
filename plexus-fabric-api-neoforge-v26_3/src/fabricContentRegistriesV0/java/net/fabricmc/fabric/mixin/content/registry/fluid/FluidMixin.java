package net.fabricmc.fabric.mixin.content.registry.fluid;

import net.fabricmc.fabric.impl.content.registry.fluid.NeoForgeFluidTypes;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Plexus Mod Loader (ADR-0031): NeoForge's Fluid.getFluidType() caches CommonHooks.getVanillaFluidType, which
// throws for a fluid neither vanilla nor NeoForge's. Fluids of Fabric mods do not override it; they get
// NeoForgeFluidTypes.fluidTypeOf, uncached, so that it follows the tags.
@Mixin(Fluid.class)
abstract class FluidMixin {
    @Inject(method = "getFluidType", at = @At("HEAD"), cancellable = true)
    private void fabric_customFluidType(CallbackInfoReturnable<FluidType> cir) {
        Fluid fluid = (Fluid) (Object) this;
        if (!NeoForgeFluidTypes.knownToNeoForge(fluid)) {
            cir.setReturnValue(NeoForgeFluidTypes.fluidTypeOf(fluid));
        }
    }
}
