package dev.plexusmodloader.game.layer.v26_3;

import dev.plexusmodloader.api.event.loot.LootDrops;
import dev.plexusmodloader.api.event.loot.LootEvents;
import dev.plexusmodloader.api.inventory.ItemStackView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.game.version.layer.GameLayerContext;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Rolls a loot table for {@code LootEvents.MODIFY}: the hook at the head of {@code LootTable.getRandomItems(LootContext,
 * Consumer)} (the method every roll passes) rolls the table itself, lets the listeners edit the items, and hands them to
 * the game's consumer. A loot table does not know its own ID in this version, so the IDs are read from the server's
 * loot tables once per data reload.
 */
final class LootRolls {

    /** Set while this class rolls a table, so that its own call is not hooked again. */
    private static final ThreadLocal<Boolean> ROLLING = new ThreadLocal<>();

    private static final Object LOCK = new Object();
    private static Object generation;
    private static Map<LootTable, ResourceId> ids = Map.of();

    private LootRolls() {}

    /** Returns {@code true} when it handled the roll (the game must not roll the table again). */
    static boolean roll(LootTable table, LootContext context, Consumer<ItemStack> sink) {
        if (ROLLING.get() != null) {
            return false;
        }
        GameLayerContext layer = LayerState.context().orElse(null);
        if (layer == null || !layer.events().hasListeners(LootEvents.MODIFY)) {
            return false;
        }
        ResourceId id = idOf(context.getLevel().getServer(), table);
        if (id == null) {
            return false;
        }
        List<ItemStack> rolled = new ArrayList<>();
        ROLLING.set(Boolean.TRUE);
        try {
            table.getRandomItems(context, rolled::add);
        } finally {
            ROLLING.remove();
        }
        layer.events()
                .publish(LootEvents.MODIFY, listener -> listener.onModify(id, new Drops(rolled, context.getRandom())));
        rolled.forEach(sink);
        return true;
    }

    private static ResourceId idOf(MinecraftServer server, LootTable table) {
        synchronized (LOCK) {
            Object current = server.reloadableRegistries();
            if (current != generation) {
                Map<LootTable, ResourceId> found = new IdentityHashMap<>();
                scan(server, found);
                ids = found;
                generation = current;
            }
            return ids.get(table);
        }
    }

    private static void scan(MinecraftServer server, Map<LootTable, ResourceId> into) {
        ReloadableServerRegistries.Holder holder = server.reloadableRegistries();
        holder.lookup()
                .lookupOrThrow(Registries.LOOT_TABLE)
                .listElementIds()
                .forEach(key -> into.put(holder.getLootTable(key), Views.id(key.identifier())));
    }

    private static ItemStackView view(ItemStack stack) {
        return new ItemStackView(Views.id(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount());
    }

    private record Drops(List<ItemStack> stacks, net.minecraft.util.RandomSource source) implements LootDrops {

        @Override
        public java.util.random.RandomGenerator random() {
            return new java.util.random.RandomGenerator() {
                @Override
                public long nextLong() {
                    return source.nextLong();
                }
            };
        }

        @Override
        public List<ItemStackView> items() {
            return stacks.stream().map(LootRolls::view).toList();
        }

        @Override
        public void add(ResourceId item, int count) {
            if (count < 1) {
                throw new IllegalArgumentException("count must be at least 1: " + count);
            }
            Item type = Views.item(item);
            int max = Math.max(1, new ItemStack(type).getMaxStackSize());
            for (int left = count; left > 0; left -= max) {
                stacks.add(new ItemStack(type, Math.min(left, max)));
            }
        }

        @Override
        public int removeIf(Predicate<ItemStackView> filter) {
            int removed = 0;
            for (Iterator<ItemStack> it = stacks.iterator(); it.hasNext(); ) {
                if (filter.test(view(it.next()))) {
                    it.remove();
                    removed++;
                }
            }
            return removed;
        }
    }
}
