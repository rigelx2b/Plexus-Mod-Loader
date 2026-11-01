package net.fabricmc.fabric.impl.content.registry.fluid;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.entity.living.LivingBreatheEvent;
import net.minecraftforge.fluids.FluidType;

// Plexus Mod Loader (ADR-0031): what this module does through MinecraftForge's fluid hooks (javap, 26.2-65.1.5 and
// 26.3-66.1.0), where Forge replaced the vanilla code Fabric API's mixins change.
public final class ForgeFluidHooks implements ModInitializer {
    @Override
    public void onInitialize() {
        LivingBreatheEvent.BUS.addListener(ForgeFluidHooks::drownInCustomFluids);
    }

    // Vanilla's LivingEntity.baseTick takes air while the eyes are in water, outside a bubble column, unless the entity
    // breathes underwater, has water breathing or is an invulnerable player; Fabric API's customFluidDrowning counts a
    // custom fluid whose behaviour drowns as water there. Forge moved that code into ForgeHooks.onLivingBreathe, which
    // decides by the fluid type at the eyes and lets LivingBreatheEvent change the answer.
    private static void drownInCustomFluids(LivingBreatheEvent event) {
        LivingEntity entity = event.getEntity();
        if (!event.canBreathe() || !eyesInDrowningCustomFluid(entity)) {
            return;
        }
        if (entity.level()
                .getBlockState(BlockPos.containing(entity.getX(), entity.getEyeY(), entity.getZ()))
                .is(Blocks.BUBBLE_COLUMN)) {
            return;
        }
        if (entity.canBreatheUnderwater()
                || MobEffectUtil.hasWaterBreathing(entity)
                || (entity instanceof Player player && player.getAbilities().invulnerable)) {
            return;
        }
        event.setCanBreathe(false);
    }

    private static boolean eyesInDrowningCustomFluid(LivingEntity entity) {
        for (TagKey<Fluid> tagKey : ((InternalEntityFluidExtension) entity).fabric_api$getTouchedCustomFluids()) {
            if (entity.isEyeInFluid(tagKey)
                    && EntityFluidInteractionRegistryImpl.getFluidBehavior(tagKey)
                            .canDrownInFluid(tagKey, entity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether Forge's own lookup ({@code ForgeHooks.getVanillaFluidType}) knows {@code fluid}: vanilla's fluids and
     * Forge's milk. Forge's own fluids override {@code Fluid.getFluidType()} and never ask it.
     */
    public static boolean knownToForge(Fluid fluid) {
        return fluid == Fluids.EMPTY
                || fluid.isSame(Fluids.WATER)
                || fluid.isSame(Fluids.LAVA)
                || ForgeMod.MILK.filter(fluid::isSame).isPresent()
                || ForgeMod.FLOWING_MILK.filter(fluid::isSame).isPresent();
    }

    /**
     * The fluid type of a fluid that Forge does not know, such as one a Fabric mod registers: Forge's lookup throws
     * ("Mod fluids must override getFluidType.") when an entity touches it. Under Fabric Loader the game treats a fluid
     * in the water or lava tag as water or lava, and this module's own tracking handles the other custom fluids; Forge's
     * water and lava types and its empty type, which Forge's entity fluid tracking skips, do the same here. Asked each
     * time, so that the answer follows the tags as they load.
     */
    public static FluidType fluidTypeOf(Fluid fluid) {
        if (fluid.is(FluidTags.WATER)) {
            return ForgeMod.WATER_TYPE.get();
        }
        if (fluid.is(FluidTags.LAVA)) {
            return ForgeMod.LAVA_TYPE.get();
        }
        return ForgeMod.EMPTY_TYPE.get();
    }
}
