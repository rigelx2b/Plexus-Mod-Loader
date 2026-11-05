package dev.plexusmodloader.game.layer.v1_20_1.entity;

import dev.plexusmodloader.api.content.effect.EffectDefinition;
import dev.plexusmodloader.api.entity.LivingEntityView;
import dev.plexusmodloader.game.layer.v1_20_1.Views;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/** A mod status effect that runs the mod's behaviour every {@code tickInterval} ticks. */
final class PlexusMobEffect extends MobEffect {

    private final EffectDefinition definition;

    PlexusMobEffect(MobEffectCategory category, int color, EffectDefinition definition) {
        super(category, color);
        this.definition = definition;
    }

    @Override
    public boolean isDurationEffectTick(int remainingDuration, int amplifier) {
        return remainingDuration % definition.tickInterval() == 0;
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        definition.behavior().onTick((LivingEntityView) Views.entity(entity), amplifier);
    }
}
