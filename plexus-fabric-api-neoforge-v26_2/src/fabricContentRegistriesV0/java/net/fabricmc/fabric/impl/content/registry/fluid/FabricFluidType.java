package net.fabricmc.fabric.impl.content.registry.fluid;

import net.fabricmc.fabric.api.registry.fluid.FluidBehavior;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidType;

// Plexus Mod Loader (ADR-0031): the NeoForge fluid type of the fluids in a tag a Fabric mod gave a fluid behaviour
// (EntityFluidInteractionRegistry). This module's mixins, from Forgified Fabric API, find a custom fluid through the
// fluid types of its tag's fluids, and NeoForge decides swimming, drowning, boats and movement in a fluid by its type
// (javap, 26.2.0.93 and 26.3.0.98: canStartSwimming, CommonHooks.onLivingBreathe, AbstractBoat.canBoatInFluid,
// LivingEntity.travelInFluid through FluidType.move). Under Sinytra Connector, Connector gives Fabric fluids their
// types; here each answer is the tag's FluidBehavior's, as Fabric API's own mixins ask it under Fabric Loader. Not in
// NeoForge's fluid type registry: no NeoForge code looks this type up by name, and it exists only while the game runs.
public final class FabricFluidType extends FluidType {

    private final TagKey<Fluid> tag;

    FabricFluidType(TagKey<Fluid> tag) {
        super(Properties.create().descriptionId("fluid_type." + tag.location().toLanguageKey()));
        this.tag = tag;
    }

    private FluidBehavior behavior() {
        return EntityFluidInteractionRegistryImpl.getFluidBehavior(tag);
    }

    @Override
    public boolean canSwim(Entity entity) {
        return behavior().canSwimInFluid(tag, entity);
    }

    @Override
    public boolean canDrownIn(LivingEntity entity) {
        return behavior().canDrownInFluid(tag, entity);
    }

    @Override
    public boolean supportsBoating(AbstractBoat boat) {
        return behavior().canSupportBoat(tag, boat);
    }

    @Override
    public boolean supportsBoating(FluidState state, AbstractBoat boat) {
        return behavior().canSupportBoat(tag, boat);
    }

    @Override
    public boolean canExtinguish(Entity entity) {
        // Under Fabric Loader only water and rain put out a burning entity.
        return false;
    }

    /**
     * Vanilla's travelInFluid works out these values before it moves the entity in water or lava, and passes them to
     * the fluid behaviour (Fabric API's travelInCustomFluid); NeoForge calls this at the same point, before any change.
     */
    @Override
    public boolean move(LivingEntity entity, Vec3 input, double gravity) {
        boolean isFalling = entity.getDeltaMovement().y <= 0.0;
        behavior().travelInFluid(tag, entity, input, gravity, isFalling, entity.getY());
        return true;
    }

    @Override
    public String toString() {
        // FluidType.toString looks the type up in NeoForge's registry, where this one is not.
        return "fabric fluid type of #" + tag.location();
    }
}
