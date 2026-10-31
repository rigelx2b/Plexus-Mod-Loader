package net.fabricmc.fabric.mixin.client.rendering;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.GuiLayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Plexus Mod Loader (ADR-0031), in place of Forgified Fabric API's FabricRenderingImpl (a NeoForge @Mod listening on
// its mod bus): NeoForge posts RegisterGuiLayersEvent to the NeoForge mods' buses, and this module runs as a Fabric mod
// with none; Fabric API's HUD elements join right after NeoForge's mods got the event.
@Mixin(GuiLayerManager.class)
abstract class GuiLayerManagerMixin {
    @WrapOperation(
            method = "initModdedLayers",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/neoforged/fml/ModLoader;postEvent(Lnet/neoforged/bus/api/Event;)V"))
    private void registerFabricHudElements(Event event, Operation<Void> original) {
        original.call(event);
        if (event instanceof RegisterGuiLayersEvent layers) {
            HudElementRegistryImpl.register(layers);
        }
    }
}
