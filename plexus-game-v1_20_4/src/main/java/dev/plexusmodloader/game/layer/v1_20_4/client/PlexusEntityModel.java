package dev.plexusmodloader.game.layer.v1_20_4.client;

import dev.plexusmodloader.api.content.entity.model.EntityModelDefinition;
import dev.plexusmodloader.api.content.entity.model.KeyframeAnimation;
import dev.plexusmodloader.api.content.entity.model.ModelBox;
import dev.plexusmodloader.api.content.entity.model.ModelPartDefinition;
import dev.plexusmodloader.api.content.entity.model.PartAnimation;
import dev.plexusmodloader.game.layer.v1_20_4.entity.PlexusMob;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * A box model built from a mod's {@link EntityModelDefinition}, animated by part and by keyframe animations that are
 * baked into the game's own {@link AnimationDefinition}. 1.20.4 animates a {@link HierarchicalModel} from the mob, its
 * walk values and its {@code AnimationState}s, where later versions use a render state.
 */
final class PlexusEntityModel extends HierarchicalModel<PlexusMob> {

    private static final float DEGREES = Mth.DEG_TO_RAD;
    private static final Vector3f CACHE = new Vector3f();

    private final ModelPart root;
    private final Map<ModelPart, PartAnimation> animations;
    private final List<AnimationDefinition> always;
    private final List<AnimationDefinition> whileMoving;

    /** In {@link EntityModelDefinition#onDemandAnimations()} order. */
    private final List<AnimationDefinition> onDemand;

    private final List<String> onDemandNames;

    private PlexusEntityModel(
            ModelPart root, Map<ModelPart, PartAnimation> animations, EntityModelDefinition definition) {
        this.root = root;
        this.animations = animations;
        List<AnimationDefinition> always = new java.util.ArrayList<>();
        List<AnimationDefinition> whileMoving = new java.util.ArrayList<>();
        Map<String, AnimationDefinition> byName = new LinkedHashMap<>();
        definition.animations().forEach((name, animation) -> {
            AnimationDefinition baked = toGame(animation);
            switch (animation.trigger()) {
                case ALWAYS -> always.add(baked);
                case WHILE_MOVING -> whileMoving.add(baked);
                case ON_DEMAND -> byName.put(name, baked);
            }
        });
        this.always = List.copyOf(always);
        this.whileMoving = List.copyOf(whileMoving);
        this.onDemandNames = definition.onDemandAnimations();
        this.onDemand = onDemandNames.stream().map(byName::get).toList();
    }

    /** The game's form of {@code animation}: degrees to radians, y up to y down, scale factor to offset. */
    static AnimationDefinition toGame(KeyframeAnimation animation) {
        AnimationDefinition.Builder builder = AnimationDefinition.Builder.withLength(animation.lengthSeconds());
        if (animation.looping()) {
            builder.looping();
        }
        for (KeyframeAnimation.Channel channel : animation.channels()) {
            Keyframe[] keyframes = channel.keyframes().stream()
                    .map(keyframe -> new Keyframe(
                            keyframe.time(),
                            switch (channel.target()) {
                                case ROTATION -> KeyframeAnimations.degreeVec(keyframe.x(), keyframe.y(), keyframe.z());
                                case POSITION -> KeyframeAnimations.posVec(keyframe.x(), keyframe.y(), keyframe.z());
                                case SCALE -> KeyframeAnimations.scaleVec(keyframe.x(), keyframe.y(), keyframe.z());
                            },
                            switch (keyframe.interpolation()) {
                                case LINEAR -> AnimationChannel.Interpolations.LINEAR;
                                case SMOOTH -> AnimationChannel.Interpolations.CATMULLROM;
                            }))
                    .toArray(Keyframe[]::new);
            AnimationChannel.Target target = switch (channel.target()) {
                case ROTATION -> AnimationChannel.Targets.ROTATION;
                case POSITION -> AnimationChannel.Targets.POSITION;
                case SCALE -> AnimationChannel.Targets.SCALE;
            };
            builder.addAnimation(channel.part(), new AnimationChannel(target, keyframes));
        }
        return builder.build();
    }

    static PlexusEntityModel create(EntityModelDefinition definition) {
        MeshDefinition mesh = new MeshDefinition();
        Map<String, PartDefinition> meshParts = new HashMap<>();
        for (ModelPartDefinition part : definition.parts()) {
            PartDefinition parent = part.parent().map(meshParts::get).orElse(mesh.getRoot());
            CubeListBuilder cubes = CubeListBuilder.create();
            for (ModelBox box : part.boxes()) {
                cubes.texOffs(box.u(), box.v())
                        .mirror(box.mirror())
                        .addBox(box.x(), box.y(), box.z(), box.width(), box.height(), box.depth());
            }
            meshParts.put(
                    part.name(),
                    parent.addOrReplaceChild(
                            part.name(),
                            cubes,
                            PartPose.offsetAndRotation(
                                    part.pivotX(),
                                    part.pivotY(),
                                    part.pivotZ(),
                                    part.rotationX() * DEGREES,
                                    part.rotationY() * DEGREES,
                                    part.rotationZ() * DEGREES)));
        }
        ModelPart root = LayerDefinition.create(mesh, definition.textureWidth(), definition.textureHeight())
                .bakeRoot();
        Map<String, ModelPart> baked = new HashMap<>();
        Map<ModelPart, PartAnimation> animations = new LinkedHashMap<>();
        for (ModelPartDefinition part : definition.parts()) {
            ModelPart parent = part.parent().map(baked::get).orElse(root);
            ModelPart modelPart = parent.getChild(part.name());
            baked.put(part.name(), modelPart);
            if (part.animation() != PartAnimation.NONE) {
                animations.put(modelPart, part.animation());
            }
        }
        return new PlexusEntityModel(root, animations, definition);
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(
            PlexusMob mob,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch) {
        root.getAllParts().forEach(ModelPart::resetPose);
        float swing = Mth.cos(limbSwing * 0.6662f) * 1.4f * limbSwingAmount;
        animations.forEach((part, animation) -> {
            switch (animation) {
                case HEAD -> {
                    part.xRot += headPitch * DEGREES;
                    part.yRot += netHeadYaw * DEGREES;
                }
                case WALK_SWING -> part.xRot += swing;
                case WALK_SWING_OPPOSITE -> part.xRot -= swing;
                case BOB -> part.y += Mth.sin(ageInTicks * 0.1f) * 1.0f;
                case SPIN -> part.yRot += ageInTicks * 0.1f;
                case NONE -> {}
            }
        });
        for (AnimationDefinition animation : always) {
            KeyframeAnimations.animate(this, animation, (long) (ageInTicks * 50), 1.0f, CACHE);
        }
        for (AnimationDefinition animation : whileMoving) {
            animateWalk(animation, limbSwing, limbSwingAmount, 2.0f, 2.5f);
        }
        net.minecraft.world.entity.AnimationState[] states = mob.animationStates();
        for (int i = 0; i < onDemand.size() && i < states.length; i++) {
            animate(states[i], onDemand.get(i), ageInTicks);
        }
    }

    /** Number of keyframe animations of every trigger. */
    int keyframeAnimations() {
        return always.size() + whileMoving.size() + onDemand.size();
    }

    List<String> onDemandNames() {
        return onDemandNames;
    }

    int animatedParts() {
        return animations.size();
    }
}
