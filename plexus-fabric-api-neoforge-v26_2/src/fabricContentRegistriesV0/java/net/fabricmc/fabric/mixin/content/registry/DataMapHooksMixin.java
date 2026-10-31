// Source: Sinytra Forgified Fabric API, branch 26.2.x, commit 40f8ce0,
// fabric-content-registries-v0/src/main/java/net/fabricmc/fabric/mixin/content/registry/DataMapHooksMixin.java (written
// in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.content.registry;

import com.llamalad7.mixinextras.sugar.Local;
import net.fabricmc.fabric.api.registry.FuelValueEvents;
import net.fabricmc.fabric.impl.content.registry.FuelRegistryEventsContextImpl;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.block.entity.FuelValues;
import net.neoforged.neoforge.common.DataMapHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DataMapHooks.class)
public class DataMapHooksMixin {

    @Inject(
            method = "populateFuelValues",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/entity/FuelValues$Builder;build()Lnet/minecraft/world/level/block/entity/FuelValues;"))
    private static void modifyFuelBurnTimes(
            RegistryAccess registries,
            FeatureFlagSet features,
            CallbackInfoReturnable<FuelValues> cit,
            @Local FuelValues.Builder builder) {
        final var context = new FuelRegistryEventsContextImpl(registries, features, 200);

        FuelValueEvents.BUILD.invoker().build(builder, context);

        FuelValueEvents.EXCLUSIONS.invoker().buildExclusions(builder, context);
    }
}
