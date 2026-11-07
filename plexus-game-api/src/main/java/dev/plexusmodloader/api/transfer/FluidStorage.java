package dev.plexusmodloader.api.transfer;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/**
 * The fluid in a block that holds one: in the vanilla game that is the cauldron (water, lava, powder snow). Get it from
 * {@link dev.plexusmodloader.api.world.WorldView#fluidStorage}.
 *
 * <p>Amounts are in <em>droplets</em>, {@link #DROPLETS_PER_BUCKET} to a bucket (the unit Fabric API uses), so a third of
 * a cauldron is a whole number. A cauldron moves whole fill levels only: water and powder snow hold 3 levels of {@link
 * #DROPLETS_PER_LEVEL}, lava is all or nothing. A cauldron holds one fluid at a time.
 *
 * <p>Only the game's cauldrons offer a fluid storage; blocks of mods cannot register one yet.
 *
 * <p><b>Thread:</b> the server thread. The storage reads the live block: ask again after the block changed.
 */
@ApiStability(StabilityLevel.PREVIEW)
public interface FluidStorage {

    /** Droplets in one bucket. */
    long DROPLETS_PER_BUCKET = 81_000L;

    /** Droplets in one fill level of a water or powder snow cauldron (a third of a bucket). */
    long DROPLETS_PER_LEVEL = 27_000L;

    /** The fluid inside, for example {@code minecraft:water}; {@code minecraft:empty} when the block is empty. */
    ResourceId fluid();

    /** How much of {@link #fluid()} is inside, in droplets; 0 when empty. */
    long amount();

    /** How much the block holds in total when full, in droplets. */
    long capacity();

    /**
     * Pours fluid in. Only whole fill levels go in, and only into an empty block or one that already holds this fluid.
     *
     * @param fluid the fluid, for example {@code minecraft:water}
     * @param amount how many droplets to insert (at least 1)
     * @param simulate {@code true} to only report the result, changing nothing
     * @return how many droplets were inserted (a multiple of the fill level, 0 when nothing fits or the fluid is not
     *     supported)
     * @throws IllegalArgumentException when {@code amount} is below 1
     */
    long insert(ResourceId fluid, long amount, boolean simulate);

    /**
     * Takes fluid out. Only whole fill levels come out.
     *
     * @param fluid the fluid to take; another fluid than {@link #fluid()} yields nothing
     * @param amount how many droplets to extract (at least 1)
     * @param simulate {@code true} to only report the result, changing nothing
     * @return how many droplets were extracted (a multiple of the fill level, 0 when nothing could be taken)
     * @throws IllegalArgumentException when {@code amount} is below 1
     */
    long extract(ResourceId fluid, long amount, boolean simulate);
}
