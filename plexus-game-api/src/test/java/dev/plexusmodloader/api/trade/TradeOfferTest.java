package dev.plexusmodloader.api.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.resource.ResourceId;
import org.junit.jupiter.api.Test;

class TradeOfferTest {

    private static final ResourceId EMERALD = ResourceId.minecraft("emerald");
    private static final ResourceId DIAMOND = ResourceId.minecraft("diamond");

    @Test
    void ofUsesTheDefaults() {
        TradeOffer offer = TradeOffer.of(EMERALD, 3, DIAMOND, 1);

        assertEquals(3, offer.buy().count());
        assertEquals(DIAMOND, offer.sell().item());
        assertTrue(offer.secondBuy().isEmpty());
        assertEquals(TradeOffer.DEFAULT_MAX_USES, offer.maxUses());
        assertEquals(TradeOffer.DEFAULT_EXPERIENCE, offer.experience());
        assertEquals(TradeOffer.DEFAULT_PRICE_MULTIPLIER, offer.priceMultiplier());
    }

    @Test
    void withersReturnChangedCopies() {
        TradeOffer base = TradeOffer.of(EMERALD, 1, DIAMOND, 1);
        TradeOffer changed = base.withSecondBuy(ResourceId.minecraft("gold_ingot"), 2)
                .withMaxUses(4)
                .withExperience(0)
                .withPriceMultiplier(0.2f);

        assertTrue(base.secondBuy().isEmpty());
        assertEquals(2, changed.secondBuy().orElseThrow().count());
        assertEquals(4, changed.maxUses());
        assertEquals(0, changed.experience());
        assertEquals(0.2f, changed.priceMultiplier());
        assertEquals(base, TradeOffer.of(EMERALD, 1, DIAMOND, 1));
    }

    @Test
    void refusesCountsOutsideTheStackSize() {
        assertThrows(IllegalArgumentException.class, () -> TradeOffer.of(EMERALD, 0, DIAMOND, 1));
        assertThrows(
                IllegalArgumentException.class, () -> TradeOffer.of(EMERALD, 1, DIAMOND, TradeOffer.MAX_STACK + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> TradeOffer.of(EMERALD, 1, DIAMOND, 1).withSecondBuy(EMERALD, 65));
    }

    @Test
    void refusesAirAndBadNumbers() {
        assertThrows(IllegalArgumentException.class, () -> TradeOffer.of(ResourceId.minecraft("air"), 1, DIAMOND, 1));
        TradeOffer offer = TradeOffer.of(EMERALD, 1, DIAMOND, 1);
        assertThrows(IllegalArgumentException.class, () -> offer.withMaxUses(0));
        assertThrows(IllegalArgumentException.class, () -> offer.withExperience(-1));
        assertThrows(IllegalArgumentException.class, () -> offer.withPriceMultiplier(-0.1f));
    }
}
