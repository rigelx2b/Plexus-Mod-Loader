package dev.plexusmodloader.game.layer.v26_1.content;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntSortedMap;
import it.unimi.dsi.fastutil.objects.Object2IntSortedMaps;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.FuelValues;

/**
 * Builds a {@code FuelValues} that holds the game's burn times and the mod items' (ADR-0040). The game's class
 * keeps its map in a private field and builds only through a private constructor, so both are reached by method
 * handles.
 */
public final class FuelValuesExtension {

    private static final MethodHandle VALUES;
    private static final MethodHandle CONSTRUCTOR;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(FuelValues.class, MethodHandles.lookup());
            VALUES = lookup.findGetter(FuelValues.class, "values", Object2IntSortedMap.class);
            CONSTRUCTOR = lookup.findConstructor(
                    FuelValues.class, MethodType.methodType(void.class, Object2IntSortedMap.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FuelValuesExtension() {}

    /** The fuel values of the game plus the mod items'; the game's own object when no mod declared a fuel. */
    @SuppressWarnings("unchecked")
    public static FuelValues extend(FuelValues vanilla) {
        Map<Item, Integer> mod = ModFuel.burnTicks();
        if (mod.isEmpty()) {
            return vanilla;
        }
        try {
            Object2IntMap<Item> merged =
                    new Object2IntLinkedOpenHashMap<>((Object2IntSortedMap<Item>) VALUES.invoke(vanilla));
            for (Map.Entry<Item, Integer> fuel : mod.entrySet()) {
                merged.put(fuel.getKey(), fuel.getValue().intValue());
            }
            return (FuelValues) CONSTRUCTOR.invoke(
                    Object2IntSortedMaps.unmodifiable((Object2IntSortedMap<Item>) (Object2IntMap<?>) merged));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Extending the fuel values failed", e);
        }
    }
}
