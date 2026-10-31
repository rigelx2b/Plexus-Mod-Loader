// Source: Sinytra Forgified Fabric API, branch 26.2.x, commit 40f8ce0,
// fabric-content-registries-v0/src/main/java/net/fabricmc/fabric/impl/content/registry/DataMapModifications.java
// (written
// in Forgified Fabric API).
// Plexus Mod Loader (ADR-0031): reformatted; without compostables (Fabric API 26.3 has no compostable registry
// and NeoForge 26.3 no compostables data map); NeoForge 26.3's oxidizables and waxables data maps return what Fabric
// mods registered (OxidizableBlocksRegistryImpl), since NeoForge 26.3 no longer falls back to the game's maps.
package net.fabricmc.fabric.impl.content.registry;

import net.fabricmc.fabric.api.registry.VillagerInteractionRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;
import net.neoforged.neoforge.registries.datamaps.builtin.Oxidizable;
import net.neoforged.neoforge.registries.datamaps.builtin.RaidHeroGift;
import net.neoforged.neoforge.registries.datamaps.builtin.Waxable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public class DataMapModifications {
    @SuppressWarnings("unchecked")
    public static <A, T> void modify(
            Registry<T> registry, DataMapType<T, A> type, ResourceKey<T> key, CallbackInfoReturnable<A> cir) {
        if (type == NeoForgeDataMaps.RAID_HERO_GIFTS) {
            registry.get(key).ifPresent(holder -> {
                var fromCustom = VillagerInteractionRegistries.MODIFIED_GIFTS.get(holder.value());
                if (fromCustom != null) {
                    cir.setReturnValue((A) new RaidHeroGift(fromCustom));
                }
            });
        } else if (type == NeoForgeDataMaps.OXIDIZABLES) {
            registry.get(key).ifPresent(holder -> {
                var fromCustom = OxidizableBlocksRegistryImpl.NEXT_STAGES.get(holder.value());
                if (fromCustom != null) {
                    // Fabric API registers its own axe scraping (BlockTransformerHelperImpl): no transform here.
                    cir.setReturnValue((A) new Oxidizable(fromCustom, false));
                }
            });
        } else if (type == NeoForgeDataMaps.WAXABLES) {
            registry.get(key).ifPresent(holder -> {
                var fromCustom = OxidizableBlocksRegistryImpl.WAXED.get(holder.value());
                if (fromCustom != null) {
                    cir.setReturnValue((A) new Waxable(fromCustom, false));
                }
            });
        }
    }
}
