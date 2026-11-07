package dev.plexusmodloader.game.layer.v1_21_1;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.trade.TradeOffer;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.VillagerTradeDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.VillagerTradeRemovalDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.WanderingTradeDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.WanderingTradeRemovalDeclaration;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds the trades of {@code TradeRegistrar} to the game's trade pools when a server creates its levels. 1.21.1 keeps
 * its pools in public static fields of {@code VillagerTrades} whose leaves are {@code ItemListing} arrays; a villager
 * draws its trades from the array of its profession and level, and the wandering trader from several arrays (buying,
 * common, rare). This replaces each array a mod added to with the game's original array plus the mod's listings, so
 * applying twice (a second server in the same JVM) never adds a trade twice.
 *
 * <p>The game has a second set of pools for the trade rebalance feature ({@code EXPERIMENTAL_*}) that it uses when that
 * feature is enabled (game tests enable every feature); the mod trades go into both.
 */
public final class TradeInstaller {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    /** The villager pools: profession -> level -> listings. */
    private static final List<Map<?, Int2ObjectMap<VillagerTrades.ItemListing[]>>> VILLAGER_POOLS =
            List.of(VillagerTrades.TRADES, VillagerTrades.EXPERIMENTAL_TRADES);

    /** The game's own array of each pool a mod added to, taken before the first change. */
    private static final Map<Object, VillagerTrades.ItemListing[]> ORIGINALS = new HashMap<>();

    private TradeInstaller() {}

    private record PoolKey(String pool, Object profession, int level) {}

    static void apply(
            List<VillagerTradeDeclaration> villager,
            List<WanderingTradeDeclaration> wandering,
            List<VillagerTradeRemovalDeclaration> villagerRemovals,
            List<WanderingTradeRemovalDeclaration> wanderingRemovals) {
        if (villager.isEmpty() && wandering.isEmpty() && villagerRemovals.isEmpty() && wanderingRemovals.isEmpty()) {
            return;
        }
        Map<PoolKey, List<VillagerTrades.ItemListing>> added = new HashMap<>();
        for (VillagerTradeDeclaration declaration : villager) {
            if (!usable(declaration.ownerModId(), declaration.offer())) {
                continue;
            }
            Object profession = profession(declaration.profession());
            if (profession == null) {
                LOGGER.warn(
                        "[Plexus Mod Loader] PLEXUS-TRADE-001 mod {} adds a trade to profession {}, which is not"
                                + " registered; skipped",
                        declaration.ownerModId(),
                        declaration.profession());
                continue;
            }
            added.computeIfAbsent(new PoolKey("villager", profession, declaration.level()), key -> new ArrayList<>())
                    .add(new ModListing(declaration.offer()));
        }
        // Removals take the game's own listings whose offer sells the item out of the pool (PLEXUS-TRADE-005).
        Map<PoolKey, Set<Item>> removedBy = new HashMap<>();
        for (VillagerTradeRemovalDeclaration removal : villagerRemovals) {
            Object profession = profession(removal.profession());
            Item item = removedItem(removal.ownerModId(), removal.result());
            if (profession == null || item == null) {
                LOGGER.warn(
                        "[Plexus Mod Loader] PLEXUS-TRADE-005 mod {} removes trades of profession {} that sell {};"
                                + " the profession or the item is not registered; skipped",
                        removal.ownerModId(),
                        removal.profession(),
                        removal.result());
                continue;
            }
            for (int level = removal.level() == 0 ? 1 : removal.level();
                    level <= (removal.level() == 0 ? 5 : removal.level());
                    level++) {
                PoolKey key = new PoolKey("villager", profession, level);
                removedBy.computeIfAbsent(key, k -> new HashSet<>()).add(item);
                added.computeIfAbsent(key, k -> new ArrayList<>());
            }
        }
        int applied = 0;
        for (Map.Entry<PoolKey, List<VillagerTrades.ItemListing>> entry : added.entrySet()) {
            boolean placed = false;
            for (int i = 0; i < VILLAGER_POOLS.size(); i++) {
                Int2ObjectMap<VillagerTrades.ItemListing[]> levels =
                        VILLAGER_POOLS.get(i).get(entry.getKey().profession());
                if (levels != null) {
                    replace(
                            levels,
                            entry.getKey().level(),
                            new PoolKey(
                                    "villager" + i,
                                    entry.getKey().profession(),
                                    entry.getKey().level()),
                            entry.getValue(),
                            removedBy.getOrDefault(entry.getKey(), Set.of()));
                    placed = true;
                }
            }
            if (placed) {
                applied += entry.getValue().size();
            } else {
                LOGGER.warn(
                        "[Plexus Mod Loader] PLEXUS-TRADE-001 profession {} has no trades to add to; skipped",
                        entry.getKey().profession());
            }
        }
        Set<Item> wanderingRemoved = new HashSet<>();
        for (WanderingTradeRemovalDeclaration removal : wanderingRemovals) {
            Item item = removedItem(removal.ownerModId(), removal.result());
            if (item == null) {
                LOGGER.warn(
                        "[Plexus Mod Loader] PLEXUS-TRADE-005 mod {} removes wandering trader trades that sell {},"
                                + " which is not registered; skipped",
                        removal.ownerModId(),
                        removal.result());
                continue;
            }
            wanderingRemoved.add(item);
        }
        applied += applyWandering(wandering, wanderingRemoved);
        LOGGER.info("[Plexus Mod Loader] Added {} trade(s) to the villager and wandering trader pools", applied);
    }

    private static int applyWandering(List<WanderingTradeDeclaration> wandering, Set<Item> removed) {
        List<VillagerTrades.ItemListing> common = new ArrayList<>();
        List<VillagerTrades.ItemListing> rare = new ArrayList<>();
        for (WanderingTradeDeclaration declaration : wandering) {
            if (usable(declaration.ownerModId(), declaration.offer())) {
                (declaration.rare() ? rare : common).add(new ModListing(declaration.offer()));
            }
        }
        int applied = common.size() + rare.size();
        if (applied == 0 && removed.isEmpty()) {
            return 0;
        }
        // Key 1 is the common array (five drawn) and key 2 the rare one (one drawn); checked with javap.
        if (!common.isEmpty() || !removed.isEmpty()) {
            replace(VillagerTrades.WANDERING_TRADER_TRADES, 1, "wandering:1", common, removed);
        }
        if (!rare.isEmpty() || !removed.isEmpty()) {
            replace(VillagerTrades.WANDERING_TRADER_TRADES, 2, "wandering:2", rare, removed);
        }
        applyPairs("experimental", VillagerTrades.EXPERIMENTAL_WANDERING_TRADER_TRADES, common, rare, removed);
        return applied;
    }

    /**
     * The pair lists hold three pools (checked with a run): the one the trader buys from the player (its listings are
     * {@code EmeraldForItems}), the rare one, and the common one that draws the most.
     */
    private static void applyPairs(
            String name,
            List<Pair<VillagerTrades.ItemListing[], Integer>> pairs,
            List<VillagerTrades.ItemListing> common,
            List<VillagerTrades.ItemListing> rare,
            Set<Item> removed) {
        Pair<VillagerTrades.ItemListing[], Integer> commonPair = null;
        Pair<VillagerTrades.ItemListing[], Integer> rarePair = null;
        for (Pair<VillagerTrades.ItemListing[], Integer> pair : pairs) {
            if (pair.getLeft().length == 0
                    || pair.getLeft()[0].getClass().getSimpleName().equals("EmeraldForItems")) {
                continue;
            }
            if (commonPair == null || pair.getRight() > commonPair.getRight()) {
                if (commonPair != null) {
                    rarePair = commonPair;
                }
                commonPair = pair;
            } else {
                rarePair = pair;
            }
        }
        if (commonPair == null || rarePair == null) {
            LOGGER.warn(
                    "[Plexus Mod Loader] PLEXUS-TRADE-001 the wandering trader pools {} were not found; skipped", name);
            return;
        }
        if (!common.isEmpty() || !removed.isEmpty()) {
            setLeft(commonPair, merged(name + ":common", commonPair.getLeft(), common, removed));
        }
        if (!rare.isEmpty() || !removed.isEmpty()) {
            setLeft(rarePair, merged(name + ":rare", rarePair.getLeft(), rare, removed));
        }
    }

    private static VillagerTrades.ItemListing[] merged(
            String key,
            VillagerTrades.ItemListing[] current,
            List<VillagerTrades.ItemListing> added,
            Set<Item> removed) {
        VillagerTrades.ItemListing[] original = ORIGINALS.computeIfAbsent(key, k -> current);
        VillagerTrades.ItemListing[] merged = Arrays.copyOf(original, original.length + added.size());
        if (!removed.isEmpty()) {
            for (int i = 0; i < original.length; i++) {
                merged[i] = new WithoutResults(original[i], removed);
            }
        }
        for (int i = 0; i < added.size(); i++) {
            merged[original.length + i] = added.get(i);
        }
        return merged;
    }

    private static Item removedItem(String modId, ResourceId id) {
        return BuiltInRegistries.ITEM.containsKey(Views.id(id)) ? Views.item(id) : null;
    }

    /**
     * A game listing whose offers that sell a removed item turn into no offer. The game draws listings from a pool until
     * it has enough offers and skips a listing that returns null, so the villager takes another listing instead. The
     * wrapped listing still runs (it may search for a map structure), its offer is discarded.
     */
    private record WithoutResults(VillagerTrades.ItemListing inner, Set<Item> removed)
            implements VillagerTrades.ItemListing {

        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource random) {
            MerchantOffer offer = inner.getOffer(trader, random);
            return offer != null && removed.contains(offer.getResult().getItem()) ? null : offer;
        }
    }

    private static void setLeft(
            Pair<VillagerTrades.ItemListing[], Integer> pair, VillagerTrades.ItemListing[] listings) {
        try {
            java.lang.reflect.Field left = org.apache.commons.lang3.tuple.ImmutablePair.class.getDeclaredField("left");
            left.setAccessible(true);
            left.set(pair, listings);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PLEXUS-TRADE-004 cannot change the wandering trader's trades", e);
        }
    }

    private static void replace(
            Int2ObjectMap<VillagerTrades.ItemListing[]> levels,
            int level,
            Object key,
            List<VillagerTrades.ItemListing> added,
            Set<Item> removed) {
        VillagerTrades.ItemListing[] current =
                levels.get(level) == null ? new VillagerTrades.ItemListing[0] : levels.get(level);
        levels.put(level, merged(key.toString(), current, added, removed));
    }

    private static boolean usable(String modId, TradeOffer offer) {
        List<ResourceId> items = new ArrayList<>();
        items.add(offer.buy().item());
        offer.secondBuy().ifPresent(second -> items.add(second.item()));
        items.add(offer.sell().item());
        for (ResourceId item : items) {
            if (!BuiltInRegistries.ITEM.containsKey(Views.id(item))) {
                LOGGER.warn(
                        "[Plexus Mod Loader] PLEXUS-TRADE-001 mod {} has a trade with item {}, which is not"
                                + " registered; skipped",
                        modId,
                        item);
                return false;
            }
        }
        return true;
    }

    private static Object profession(ResourceId id) {
        return BuiltInRegistries.VILLAGER_PROFESSION.containsKey(Views.id(id))
                ? BuiltInRegistries.VILLAGER_PROFESSION.get(Views.id(id))
                : null;
    }

    private static ItemStack stack(dev.plexusmodloader.api.inventory.ItemStackView view) {
        return new ItemStack(Views.item(view.item()), view.count());
    }

    /** A mod's trade as the game's listing; a fresh offer for every villager. */
    private record ModListing(TradeOffer offer) implements VillagerTrades.ItemListing {

        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource random) {
            return new MerchantOffer(
                    cost(offer.buy()),
                    offer.secondBuy().map(ModListing::cost),
                    stack(offer.sell()),
                    offer.maxUses(),
                    offer.experience(),
                    offer.priceMultiplier());
        }

        private static net.minecraft.world.item.trading.ItemCost cost(
                dev.plexusmodloader.api.inventory.ItemStackView view) {
            return new net.minecraft.world.item.trading.ItemCost(Views.item(view.item()), view.count());
        }
    }

    /** A game offer as the API's trade; for game tests. */
    public static TradeOffer toApi(MerchantOffer offer) {
        ItemStack second = offer.getCostB();
        return new TradeOffer(
                view(offer.getBaseCostA()),
                second.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(view(second)),
                view(offer.getResult()),
                offer.getMaxUses(),
                offer.getXp(),
                offer.getPriceMultiplier());
    }

    private static dev.plexusmodloader.api.inventory.ItemStackView view(ItemStack stack) {
        return new dev.plexusmodloader.api.inventory.ItemStackView(
                Views.id(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount());
    }
}
