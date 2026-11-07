package dev.plexusmodloader.api.trade;

import dev.plexusmodloader.api.inventory.ItemStackView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;
import java.util.Optional;

/**
 * One trade a villager or wandering trader offers: the items the customer pays (one or two), the item they get, how
 * often the trade can be used before the trader restocks, the experience the trader gains per use, and how fast the
 * price reacts to demand and reputation.
 *
 * <p>A trade carries no item data (no name, enchantments, or other components): both sides are plain stacks. Build one
 * with {@link #of} and the {@code with...} methods; every method returns a new offer.
 *
 * @param buy the first item the customer pays
 * @param secondBuy the optional second item the customer pays
 * @param sell the item the customer gets
 * @param maxUses how many times the trade can be used before the trader restocks (at least 1)
 * @param experience the experience the trader gains per use (0 or more); a villager levels up from it
 * @param priceMultiplier how much one point of demand or reputation changes the price (0 or more; vanilla uses 0.05
 *     and 0.2)
 */
@ApiStability(StabilityLevel.PREVIEW)
public record TradeOffer(
        ItemStackView buy,
        Optional<ItemStackView> secondBuy,
        ItemStackView sell,
        int maxUses,
        int experience,
        float priceMultiplier) {

    /** The default for {@link #of}: the trade can be used 12 times between restocks. */
    public static final int DEFAULT_MAX_USES = 12;

    /** The default for {@link #of}: the trader gains 2 experience per use. */
    public static final int DEFAULT_EXPERIENCE = 2;

    /** The default for {@link #of}: the price multiplier of most vanilla trades. */
    public static final float DEFAULT_PRICE_MULTIPLIER = 0.05f;

    /** The most one stack may hold in a trade. */
    public static final int MAX_STACK = 64;

    public TradeOffer {
        Objects.requireNonNull(buy, "buy");
        Objects.requireNonNull(secondBuy, "secondBuy");
        Objects.requireNonNull(sell, "sell");
        check(buy, "buy");
        secondBuy.ifPresent(second -> check(second, "secondBuy"));
        check(sell, "sell");
        if (maxUses < 1) {
            throw new IllegalArgumentException("maxUses must be at least 1: " + maxUses);
        }
        if (experience < 0) {
            throw new IllegalArgumentException("experience must not be negative: " + experience);
        }
        if (priceMultiplier < 0 || Float.isNaN(priceMultiplier)) {
            throw new IllegalArgumentException("priceMultiplier must not be negative: " + priceMultiplier);
        }
    }

    private static void check(ItemStackView stack, String name) {
        if (stack.count() < 1 || stack.count() > MAX_STACK) {
            throw new IllegalArgumentException(name + " count must be from 1 to " + MAX_STACK + ": " + stack.count());
        }
        if (stack.item().equals(ResourceId.minecraft("air"))) {
            throw new IllegalArgumentException(name + " must be an item, not air");
        }
    }

    /**
     * A trade with one payment item and the default uses, experience, and price multiplier.
     *
     * @throws IllegalArgumentException when a count is not from 1 to 64 or an item is {@code minecraft:air}
     */
    public static TradeOffer of(ResourceId buy, int buyCount, ResourceId sell, int sellCount) {
        return new TradeOffer(
                new ItemStackView(buy, buyCount),
                Optional.empty(),
                new ItemStackView(sell, sellCount),
                DEFAULT_MAX_USES,
                DEFAULT_EXPERIENCE,
                DEFAULT_PRICE_MULTIPLIER);
    }

    /** The same trade with a second payment item. */
    public TradeOffer withSecondBuy(ResourceId item, int count) {
        return new TradeOffer(
                buy, Optional.of(new ItemStackView(item, count)), sell, maxUses, experience, priceMultiplier);
    }

    /** The same trade with another number of uses between restocks. */
    public TradeOffer withMaxUses(int maxUses) {
        return new TradeOffer(buy, secondBuy, sell, maxUses, experience, priceMultiplier);
    }

    /** The same trade with another experience gain per use. */
    public TradeOffer withExperience(int experience) {
        return new TradeOffer(buy, secondBuy, sell, maxUses, experience, priceMultiplier);
    }

    /** The same trade with another price multiplier. */
    public TradeOffer withPriceMultiplier(float priceMultiplier) {
        return new TradeOffer(buy, secondBuy, sell, maxUses, experience, priceMultiplier);
    }
}
