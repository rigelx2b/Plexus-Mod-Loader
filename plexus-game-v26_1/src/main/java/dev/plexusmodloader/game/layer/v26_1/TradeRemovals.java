package dev.plexusmodloader.game.layer.v26_1;

import dev.plexusmodloader.game.version.layer.NativeDeclarations.VillagerTradeRemovalDeclaration;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.WanderingTradeRemovalDeclaration;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.VillagerTrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes the game's own trades out of its trade sets when a server creates its levels ({@code
 * TradeRegistrar.removeVillagerTrades}, {@code removeWanderingTrades}). From 26.1 a trade is a {@code VillagerTrade}
 * data entry and a {@code TradeSet} (registry {@code trade_set}, ID {@code <profession>/level_<n>} or {@code
 * wandering_trader/<pool>}) holds a set of them. This replaces the list inside each matching set's {@link HolderSet}
 * (a private list field of both set kinds) with the trades whose given item is not removed. The registries are created
 * fresh for every server, so applying twice never removes from a stale list.
 */
final class TradeRemovals {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    private TradeRemovals() {}

    static void apply(
            RegistryAccess registries,
            List<VillagerTradeRemovalDeclaration> villager,
            List<WanderingTradeRemovalDeclaration> wandering) {
        if (villager.isEmpty() && wandering.isEmpty()) {
            return;
        }
        Field gives;
        try {
            gives = VillagerTrade.class.getDeclaredField("gives");
            gives.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PLEXUS-TRADE-006 cannot read what a trade gives", e);
        }
        int removedTrades = 0;
        Set<String> warned = new HashSet<>();
        for (VillagerTradeRemovalDeclaration removal : villager) {
            if (!professionKnown(removal)) {
                LOGGER.warn(
                        "[Plexus Mod Loader] PLEXUS-TRADE-005 mod {} removes trades of profession {}, which is not"
                                + " registered; skipped",
                        removal.ownerModId(),
                        removal.profession());
            }
        }
        for (Holder.Reference<TradeSet> set :
                registries.lookupOrThrow(Registries.TRADE_SET).listElements().toList()) {
            String namespace = set.key().identifier().getNamespace();
            String path = set.key().identifier().getPath();
            Set<Item> removed = new HashSet<>();
            for (VillagerTradeRemovalDeclaration removal : villager) {
                String profession = removal.profession().path();
                boolean matches = namespace.equals(removal.profession().namespace())
                        && (removal.level() == 0
                                ? path.matches(java.util.regex.Pattern.quote(profession) + "/level_[1-5]")
                                : path.equals(profession + "/level_" + removal.level()));
                Item item = removedItem(
                        warned, removal.ownerModId(), removal.profession().toString(), removal.result());
                if (matches && item != null && professionKnown(removal)) {
                    removed.add(item);
                }
            }
            if (path.startsWith("wandering_trader/")) {
                for (WanderingTradeRemovalDeclaration removal : wandering) {
                    Item item = removedItem(warned, removal.ownerModId(), "the wandering trader", removal.result());
                    if (item != null) {
                        removed.add(item);
                    }
                }
            }
            if (removed.isEmpty()) {
                continue;
            }
            HolderSet<VillagerTrade> trades = set.value().getTrades();
            List<Holder<VillagerTrade>> kept = trades.stream()
                    .filter(trade -> addedByMod(trade) || !removed.contains(given(gives, trade.value())))
                    .toList();
            if (kept.size() != trades.size()) {
                removedTrades += trades.size() - kept.size();
                setContents(trades, kept);
            }
        }
        LOGGER.info(
                "[Plexus Mod Loader] Removed {} trade(s) from the villager and wandering trader trade sets",
                removedTrades);
    }

    /**
     * Whether the trade is one that the generated data pack added for a mod ({@code GeneratedDataPack}): removals take
     * only other trades, so a mod replaces a game trade by removing it and adding its own (ADR-0044).
     */
    private static boolean addedByMod(Holder<VillagerTrade> trade) {
        return trade.unwrapKey()
                .map(key -> key.identifier().getPath().startsWith("plexus_villager_trade_")
                        || key.identifier().getPath().startsWith("plexus_wandering_trade_"))
                .orElse(false);
    }

    private static boolean professionKnown(VillagerTradeRemovalDeclaration removal) {
        return BuiltInRegistries.VILLAGER_PROFESSION.containsKey(Views.id(removal.profession()));
    }

    /** The item or null; the unknown ones are logged once per call site, which is enough for a startup message. */
    private static Item removedItem(
            Set<String> warned, String modId, String where, dev.plexusmodloader.api.resource.ResourceId id) {
        if (!BuiltInRegistries.ITEM.containsKey(Views.id(id))) {
            if (warned.add(modId + where + id)) {
                LOGGER.warn(
                        "[Plexus Mod Loader] PLEXUS-TRADE-005 mod {} removes trades of {} that give {}, which is not"
                                + " registered; skipped",
                        modId,
                        where,
                        id);
            }
            return null;
        }
        return Views.item(id);
    }

    private static Item given(Field gives, VillagerTrade trade) {
        try {
            return ((ItemStackTemplate) gives.get(trade)).item().value();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("PLEXUS-TRADE-006 cannot read what a trade gives", e);
        }
    }

    private static void setContents(HolderSet<VillagerTrade> trades, List<Holder<VillagerTrade>> kept) {
        try {
            Field contents = trades.getClass().getDeclaredField("contents");
            contents.setAccessible(true);
            contents.set(trades, kept);
            try {
                Field cached = trades.getClass().getDeclaredField("contentsSet");
                cached.setAccessible(true);
                cached.set(trades, null);
            } catch (NoSuchFieldException none) {
                // a named set keeps no cached set
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PLEXUS-TRADE-006 cannot change a trade set", e);
        }
    }
}
