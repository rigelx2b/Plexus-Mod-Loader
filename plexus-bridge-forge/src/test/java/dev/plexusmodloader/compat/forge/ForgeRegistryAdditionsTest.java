package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Fabric API's entry-added event for entries other loaders' mods added to registries Forge wraps. */
class ForgeRegistryAdditionsTest {

    /** Fabric API's {@code RegistryEntryAddedCallback}, as the event's invoker implements it. */
    public interface EntryAdded {
        void onEntryAdded(int rawId, Object id, Object entry);
    }

    /** Fabric API's {@code Event}: {@code invoker()}. */
    public static final class Event {
        private final EntryAdded invoker;

        Event(EntryAdded invoker) {
            this.invoker = invoker;
        }

        public EntryAdded invoker() {
            return invoker;
        }
    }

    /** A registry with Fabric API's {@code fabric_getAddObjectEvent()} and the game's {@code getId}/{@code getKey}. */
    public static final class Registry implements Iterable<Object> {
        final List<Object> entries = new ArrayList<>();
        Event event;

        public Event fabric_getAddObjectEvent() {
            return event;
        }

        public int getId(Object entry) {
            return entries.indexOf(entry);
        }

        public Object getKey(Object entry) {
            return "id:" + entry;
        }

        @Override
        public Iterator<Object> iterator() {
            return new ArrayList<>(entries).iterator();
        }
    }

    @Test
    void eachEntryAddedSinceTheSnapshotGetsTheEventAndSoDoTheListenersOwn() {
        Registry registry = new Registry();
        registry.entries.add("vanilla");
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add("vanilla");
        registry.entries.add("modded");
        List<String> events = new ArrayList<>();
        registry.event = new Event((rawId, id, entry) -> {
            events.add(rawId + " " + id);
            if (entry.equals("modded")) {
                registry.entries.add("follower");
            }
        });
        Map<Object, Set<Object>> before = new IdentityHashMap<>();
        before.put(registry, seen);

        ForgeRegistryAdditions.fire(before);

        assertEquals(List.of("1 id:modded", "2 id:follower"), events);
    }

    /** Without Fabric API's registry sync, the registry has no event and is left alone. */
    @Test
    void aRegistryWithoutTheEventIsLeftAlone() {
        Iterable<Object> registry = List.of("modded");
        Map<Object, Set<Object>> before = new IdentityHashMap<>();
        before.put(registry, Collections.newSetFromMap(new IdentityHashMap<>()));

        ForgeRegistryAdditions.fire(before);
    }
}
