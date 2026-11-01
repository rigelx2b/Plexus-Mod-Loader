package net.fabricmc.fabric.impl.content.registry.fluid;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;

// Plexus Mod Loader (ADR-0031): the fluid type of a fluid NeoForge does not know, such as one a Fabric mod registers.
// NeoForge's Fluid.getFluidType() (javap, 26.2.0.93 and 26.3.0.98) falls back to CommonHooks.getVanillaFluidType, which
// throws ("Mod fluids must override getFluidType.") for any other fluid; NeoForge asks it as a fluid flows and as an
// entity touches it, so a Fabric mod's fluid stopped the server.
public final class NeoForgeFluidTypes {

    private static final Map<TagKey<Fluid>, FabricFluidType> TYPES = new ConcurrentHashMap<>();

    private NeoForgeFluidTypes() {}

    /** Whether NeoForge's own lookup has a type for {@code fluid}: vanilla's empty, water and lava, and NeoForge's milk. */
    public static boolean knownToNeoForge(Fluid fluid) {
        return fluid == Fluids.EMPTY
                || fluid.isSame(Fluids.WATER)
                || fluid.isSame(Fluids.LAVA)
                || NeoForgeMod.MILK.asOptional().filter(fluid::isSame).isPresent()
                || NeoForgeMod.FLOWING_MILK.asOptional().filter(fluid::isSame).isPresent();
    }

    /**
     * Under Fabric Loader the game treats a fluid in the water or lava tag as water or lava, and a fluid in a tag a
     * Fabric mod gave a fluid behaviour as that behaviour says: here NeoForge's water and lava types, and that tag's
     * {@link FabricFluidType}. Any other fluid gets NeoForge's empty type, which NeoForge's entity fluid tracking skips.
     * Asked each time, so that the answer follows the tags as they load.
     */
    public static FluidType fluidTypeOf(Fluid fluid) {
        if (fluid.is(FluidTags.WATER)) {
            return NeoForgeMod.WATER_TYPE.value();
        }
        if (fluid.is(FluidTags.LAVA)) {
            return NeoForgeMod.LAVA_TYPE.value();
        }
        for (TagKey<Fluid> tag : EntityFluidInteractionRegistryImpl.getTrackedFluids()) {
            if (fluid.is(tag)) {
                return TYPES.computeIfAbsent(tag, FabricFluidType::new);
            }
        }
        return NeoForgeMod.EMPTY_TYPE.value();
    }
}
