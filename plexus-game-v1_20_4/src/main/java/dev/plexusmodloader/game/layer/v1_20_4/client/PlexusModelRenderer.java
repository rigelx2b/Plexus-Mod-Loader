package dev.plexusmodloader.game.layer.v1_20_4.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.plexusmodloader.game.layer.v1_20_4.entity.PlexusMob;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.AnimationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Draws a mod mob with its own box model and texture. */
final class PlexusModelRenderer extends MobRenderer<PlexusMob, PlexusEntityModel> {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    private final ResourceLocation texture;
    private final String typeName;
    private final AtomicBoolean reported = new AtomicBoolean();
    private final Set<String> reportedAnimations = ConcurrentHashMap.newKeySet();

    PlexusModelRenderer(
            EntityRendererProvider.Context context,
            PlexusEntityModel model,
            ResourceLocation texture,
            float shadow,
            String typeName) {
        super(context, model, shadow);
        this.texture = texture;
        this.typeName = typeName;
    }

    @Override
    public ResourceLocation getTextureLocation(PlexusMob mob) {
        return texture;
    }

    @Override
    public void render(
            PlexusMob mob, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light) {
        super.render(mob, yaw, partialTicks, poseStack, buffers, light);
        if (reported.compareAndSet(false, true)) {
            LOGGER.info(
                    "[Plexus Mod Loader] Drew mod mob model {} ({} animated part(s), {} keyframe animation(s))",
                    typeName,
                    getModel().animatedParts(),
                    getModel().keyframeAnimations());
        }
        List<String> names = getModel().onDemandNames();
        AnimationState[] states = mob.animationStates();
        for (int i = 0; i < states.length && i < names.size(); i++) {
            if (states[i].isStarted() && reportedAnimations.add(names.get(i))) {
                LOGGER.info("[Plexus Mod Loader] Played mod mob animation {} of {}", names.get(i), typeName);
            }
        }
    }
}
