package net.fabricmc.fabric.mixin.content.registry;

import java.util.Map;
import net.fabricmc.fabric.impl.content.registry.OxidizableBlocksRegistryImpl;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.DataMapHooks;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Plexus Mod Loader (ADR-0031). NeoForge 26.3 finds the previous oxidation stage and the unwaxed block (lightning
// strikes, WeatheringCopper.getFirst) only in the inverse maps it builds from its oxidizables and waxables data
// maps when they change; NeoForge 26.2 fell back to the game's maps, where Fabric API registers. Once NeoForge has
// rebuilt them, the blocks Fabric mods registered are added. The forward lookups go through DataMapModifications.
@Mixin(DataMapHooks.class)
abstract class DataMapHooksMixin {
    @Shadow
    @Final
    private static Map<Block, Block> INVERSE_OXIDIZABLES_DATAMAP_INTERNAL;

    @Shadow
    @Final
    private static Map<Block, Block> INVERSE_WAXABLES_DATAMAP_INTERNAL;

    @Inject(method = "onDataMapsUpdated", at = @At("TAIL"))
    private static void addFabricBlocks(DataMapsUpdatedEvent event, CallbackInfo info) {
        OxidizableBlocksRegistryImpl.addPreviousStages(INVERSE_OXIDIZABLES_DATAMAP_INTERNAL);
        OxidizableBlocksRegistryImpl.addUnwaxedBlocks(INVERSE_WAXABLES_DATAMAP_INTERNAL);
    }
}
