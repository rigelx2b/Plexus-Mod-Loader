package net.fabricmc.fabric.mixin.content.registry.fluid;

import net.fabricmc.fabric.impl.content.registry.fluid.ForgeFluidHooks;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Plexus Mod Loader (ADR-0031): MinecraftForge's Fluid.getFluidType() (javap, 26.2-65.1.5 and 26.3-66.1.0) caches
// ForgeHooks.getVanillaFluidType, which throws for a fluid neither vanilla nor Forge's; Forge's entity fluid
// tracking asks it for every fluid an entity touches. Fluids of Fabric mods do not override it; they get
// ForgeFluidHooks.fluidTypeOf.
@Mixin(Fluid.class)
abstract class FluidMixin {
    @Inject(method = "getFluidType", at = @At("HEAD"), cancellable = true)
    private void fabric_customFluidType(CallbackInfoReturnable<FluidType> cir) {
        Fluid fluid = (Fluid) (Object) this;
        if (!ForgeFluidHooks.knownToForge(fluid)) {
            cir.setReturnValue(ForgeFluidHooks.fluidTypeOf(fluid));
        }
    }
}
