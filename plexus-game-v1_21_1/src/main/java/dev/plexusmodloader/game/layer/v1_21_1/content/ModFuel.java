package dev.plexusmodloader.game.layer.v1_21_1.content;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.item.Item;

/** Burn times of mod items, read by the hooks that extend the game's fuel table (ADR-0040). */
public final class ModFuel {

    private static final Map<Item, Integer> BURN_TICKS = new LinkedHashMap<>();

    private ModFuel() {}

    static void add(Item item, int ticks) {
        BURN_TICKS.put(item, ticks);
    }

    /** The burn times of every mod fuel, by item. */
    public static Map<Item, Integer> burnTicks() {
        return BURN_TICKS;
    }
}
