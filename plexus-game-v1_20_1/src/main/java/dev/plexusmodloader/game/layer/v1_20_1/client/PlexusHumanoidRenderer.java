package dev.plexusmodloader.game.layer.v1_20_1.client;

import dev.plexusmodloader.game.layer.v1_20_1.entity.PlexusMob;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Draws a mod mob with the player-shaped model and the mod's 64×64 skin texture. */
final class PlexusHumanoidRenderer extends HumanoidMobRenderer<PlexusMob, HumanoidModel<PlexusMob>> {

    private final ResourceLocation texture;

    PlexusHumanoidRenderer(EntityRendererProvider.Context context, ResourceLocation texture) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
        this.texture = texture;
    }

    @Override
    public ResourceLocation getTextureLocation(PlexusMob mob) {
        return texture;
    }
}
