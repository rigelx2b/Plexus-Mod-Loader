package net.fabricmc.fabric.mixin.client.rendering;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.impl.client.rendering.PictureInPictureRendererRegistryImpl;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Plexus Mod Loader (ADR-0031), in place of Forgified Fabric API's FabricRenderingImpl (a NeoForge @Mod listening on
// its mod bus): NeoForge posts RegisterPictureInPictureRenderersEvent to the NeoForge mods' buses, and this module runs
// as a Fabric mod with none; Fabric API's picture-in-picture renderers join right after NeoForge's mods got the event.
@Mixin(ClientHooks.class)
abstract class ClientHooksMixin {
    @WrapOperation(
            method = "gatherPictureInPictureRenderers",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/neoforged/fml/ModLoader;postEvent(Lnet/neoforged/bus/api/Event;)V"))
    private static void registerFabricPictureInPictureRenderers(Event event, Operation<Void> original) {
        original.call(event);
        if (event instanceof RegisterPictureInPictureRenderersEvent renderers) {
            PictureInPictureRendererRegistryImpl.apply(renderers);
        }
    }
}
