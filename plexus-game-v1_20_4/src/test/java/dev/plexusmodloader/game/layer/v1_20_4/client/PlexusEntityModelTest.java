package dev.plexusmodloader.game.layer.v1_20_4.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.content.entity.model.EntityModelDefinition;
import dev.plexusmodloader.api.content.entity.model.KeyframeAnimation;
import dev.plexusmodloader.api.content.entity.model.PartAnimation;
import java.util.List;
import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.model.geom.ModelPart;
import org.junit.jupiter.api.Test;

/** A mod's box model is baked into the game's model parts and keyframe animations without a window. */
class PlexusEntityModelTest {

    private static EntityModelDefinition definition() {
        return EntityModelDefinition.builder(64, 32)
                .part("body", part -> part.pivot(0, 24, 0).box(0, 0, -4, -8, -4, 8, 8, 8))
                .part(
                        "head",
                        part -> part.parent("body")
                                .pivot(0, -8, 0)
                                .box(0, 16, -3, -6, -3, 6, 6, 6)
                                .animation(PartAnimation.HEAD))
                .animation(
                        "wobble",
                        KeyframeAnimation.builder(1.0f)
                                .looping()
                                .trigger(KeyframeAnimation.Trigger.ALWAYS)
                                .rotation("body", 0.0f, 0, 0, 0)
                                .rotation("body", 0.5f, 0, 10, 0)
                                .rotation("body", 1.0f, 0, 0, 0)
                                .build())
                .animation(
                        "wave",
                        KeyframeAnimation.builder(0.5f)
                                .trigger(KeyframeAnimation.Trigger.ON_DEMAND)
                                .position("head", 0.0f, 0, 0, 0)
                                .position("head", 0.5f, 0, 1, 0)
                                .build())
                .build();
    }

    @Test
    void thePartsAreBakedInTheirHierarchy() {
        PlexusEntityModel model = PlexusEntityModel.create(definition());
        ModelPart body = model.root().getChild("body");
        ModelPart head = body.getChild("head");
        assertEquals(24.0f, body.y, "the part's pivot is its offset");
        assertEquals(-8.0f, head.y);
        assertEquals(1, model.animatedParts(), "only the head turns with the mob");
    }

    @Test
    void keyframeAnimationsAreBakedPerTrigger() {
        PlexusEntityModel model = PlexusEntityModel.create(definition());
        assertEquals(2, model.keyframeAnimations());
        assertEquals(List.of("wave"), model.onDemandNames());
    }

    @Test
    void keyframesBecomeTheGamesDefinitionInItsUnits() {
        AnimationDefinition game =
                PlexusEntityModel.toGame(definition().animations().get("wobble"));
        assertEquals(1.0f, game.lengthInSeconds());
        assertTrue(game.looping());
        List<AnimationChannel> channels = game.boneAnimations().get("body");
        assertEquals(1, channels.size());
        // Rotation keyframes are degrees in the API and radians in the game.
        assertEquals(
                (float) Math.toRadians(10),
                channels.get(0).keyframes()[1].target().y(),
                1.0e-6f);
    }
}
