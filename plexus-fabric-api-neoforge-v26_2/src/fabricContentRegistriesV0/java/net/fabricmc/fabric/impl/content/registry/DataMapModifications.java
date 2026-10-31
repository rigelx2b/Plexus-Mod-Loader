// Source: Sinytra Forgified Fabric API, branch 26.2.x, commit 40f8ce0,
// fabric-content-registries-v0/src/main/java/net/fabricmc/fabric/impl/content/registry/DataMapModifications.java
// (written
// in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.content.registry;

import net.fabricmc.fabric.api.registry.VillagerInteractionRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.builtin.Compostable;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;
import net.neoforged.neoforge.registries.datamaps.builtin.RaidHeroGift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public class DataMapModifications {
    @SuppressWarnings("unchecked")
    public static <A, T> void modify(
            Registry<T> registry, DataMapType<T, A> type, ResourceKey<T> key, CallbackInfoReturnable<A> cir) {
        if (type == NeoForgeDataMaps.COMPOSTABLES) {
            registry.get(key).ifPresent(holder -> {
                var fromCustom = CompostableRegistryImpl.CUSTOM.get(holder.value());
                if (fromCustom != null) {
                    if (fromCustom < 0) {
                        cir.setReturnValue(null);
                    } else {
                        cir.setReturnValue((A) new Compostable(fromCustom));
                    }
                }
            });
        } else if (type == NeoForgeDataMaps.RAID_HERO_GIFTS) {
            registry.get(key).ifPresent(holder -> {
                var fromCustom = VillagerInteractionRegistries.MODIFIED_GIFTS.get(holder.value());
                if (fromCustom != null) {
                    cir.setReturnValue((A) new RaidHeroGift(fromCustom));
                }
            });
        }
    }
}
