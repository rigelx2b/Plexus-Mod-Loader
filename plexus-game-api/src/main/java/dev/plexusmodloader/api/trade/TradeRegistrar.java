package dev.plexusmodloader.api.trade;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/**
 * Adds trades to the villagers and the wandering trader of the game (since 2.1.0). Get it from {@code
 * ModContext.trades()}.
 *
 * <p><b>Pool, not guarantee.</b> A villager does not offer every trade of its profession. At each level it draws a few
 * (two at level 1 for most professions) at random from that level's pool, and a wandering trader draws five from its
 * common pool and one or two from its rare pool. A trade added here joins the pool, so it is offered by some of the
 * villagers of that profession and level, not by all. Villagers that already have their offers keep them; the added
 * trades reach villagers that generate their offers after the server loaded them.
 *
 * <p><b>When:</b> common entrypoints. The trades apply every time a server loads its data (on Minecraft 26.1 and later
 * the game reads them from a data pack the loader generates; {@code /reload} on a real world was not checked).
 *
 * <p><b>Failure:</b> a profession or item that is not registered when the server loads is logged with {@code
 * PLEXUS-TRADE-001} and that trade is skipped; the server still starts. A declaration after the registration phase
 * ended throws {@link IllegalStateException} ({@code PLEXUS-TRADE-002}). Fabric API: {@code TradeOfferHelper}; NeoForge:
 * {@code VillagerTradesEvent}, {@code WandererTradesEvent}. Removing the game's own trades is {@link #removeVillagerTrades}; changing one is removing it and adding your own.
 */
@ApiStability(StabilityLevel.PREVIEW)
public interface TradeRegistrar {

    /** The lowest villager level (apprentice is 2; a new villager with a job starts at 1). */
    int MIN_LEVEL = 1;

    /** The highest villager level (master). */
    int MAX_LEVEL = 5;

    /**
     * Adds a trade to the pool of a villager profession at a level.
     *
     * @param profession the profession, for example {@code minecraft:farmer}
     * @param level the villager level from {@value #MIN_LEVEL} (novice) to {@value #MAX_LEVEL} (master)
     * @param offer the trade
     * @throws IllegalArgumentException when the level is out of range
     * @throws IllegalStateException when registration has ended
     */
    void addVillagerTrade(ResourceId profession, int level, TradeOffer offer);

    /**
     * Adds a trade to the wandering trader's pool.
     *
     * @param offer the trade
     * @param rare {@code true} for the rare pool, from which the trader draws one or two trades; {@code false} for the
     *     common pool, from which it draws five
     * @throws IllegalStateException when registration has ended
     */
    void addWanderingTrade(TradeOffer offer, boolean rare);

    /**
     * Removes the game's own trades that sell an item from the pool of a villager profession (since 2.1.0), whoever put
     * them there; trades that other mods added with {@link #addVillagerTrade} stay. A mod replaces a trade by removing the
     * item and adding its own offer.
     *
     * <p>Removal is by <em>result</em>: a trade goes when the item it gives the player is {@code result}. Villagers that
     * already have their offers keep them. Up to 1.21.11 the pool holds code ({@code ItemListing}), so the game's
     * listing still runs and its offer is discarded; the villager then draws another listing of the pool. From 26.1 the
     * pool is data, and the trade is taken out of the trade set.
     *
     * <p><b>When:</b> common entrypoints; applied each time a server loads its data. A profession or item that is not
     * registered then is logged with {@code PLEXUS-TRADE-005} and skipped. A pool without a matching trade is not an
     * error.
     *
     * @param profession the profession, for example {@code minecraft:farmer}
     * @param level the villager level from {@value #MIN_LEVEL} to {@value #MAX_LEVEL}, or 0 for every level
     * @param result the item the trade gives, for example {@code minecraft:bread}
     * @throws IllegalArgumentException when the level is neither 0 nor in range ({@code PLEXUS-TRADE-003})
     * @throws IllegalStateException when registration has ended ({@code PLEXUS-TRADE-002})
     */
    void removeVillagerTrades(ResourceId profession, int level, ResourceId result);

    /**
     * Removes the game's own wandering trader trades that sell an item, from the common and the rare pool (since
     * 2.1.0). Same rules as {@link #removeVillagerTrades}.
     *
     * @param result the item the trade gives
     * @throws IllegalStateException when registration has ended ({@code PLEXUS-TRADE-002})
     */
    void removeWanderingTrades(ResourceId result);
}
