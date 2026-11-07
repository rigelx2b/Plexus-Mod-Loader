package dev.plexusmodloader.api.registry;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A registry that a mod owns: a named, ordered set of entries of one type that other mods can fill and read.
 *
 * <p>This is the Native API's custom registry (ADR-0042). The owner creates it with
 * {@link #create(ResourceId, Class)} while it initialises, other mods find it with {@link #find(ResourceId, Class)}
 * and add entries with {@link #register(ResourceId, Object)}, and the owner calls {@link #freeze()} once the
 * entries should not change; after that, registering fails. Entries get raw ids (their position) in registration
 * order. The raw ids are the same on every side only if every side registers the same entries in the same order,
 * so data that crosses the network or is saved should use the entry's {@link ResourceId}, not the raw id.
 *
 * <p>Not provided: synchronising the entries to clients, saving a registry in a world, and entries that
 * come from data packs. The registry holds plain Java values; it does not hold or expose Minecraft types.
 *
 * <p>Threads: all methods are safe to call from any thread (each takes the registry's lock briefly). Failure: registering after the freeze, or the same id twice, throws; {@link #create(ResourceId, Class)} throws
 * when the id is taken.
 *
 * @param <T> the entry type
 */
@ApiStability(StabilityLevel.PREVIEW)
public final class ModRegistry<T> {

    private static final Map<ResourceId, ModRegistry<?>> BY_ID = new ConcurrentHashMap<>();

    private final ResourceId id;
    private final Class<T> type;
    private final Map<ResourceId, T> entries = new LinkedHashMap<>();
    private final Map<T, ResourceId> ids = new IdentityHashMap<>();
    private final List<T> byRawId = new ArrayList<>();
    private final List<Consumer<ModRegistry<T>>> freezeListeners = new ArrayList<>();
    private boolean frozen;

    private ModRegistry(ResourceId id, Class<T> type) {
        this.id = id;
        this.type = type;
    }

    /**
     * Creates and publishes a registry.
     *
     * @param id the registry's name, normally in the owner's namespace, for example {@code my_mod:spells}
     * @param type the entry type
     * @throws IllegalStateException when a registry with this id exists
     */
    public static <T> ModRegistry<T> create(ResourceId id, Class<T> type) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        ModRegistry<T> registry = new ModRegistry<>(id, type);
        if (BY_ID.putIfAbsent(id, registry) != null) {
            throw new IllegalStateException("A registry named " + id + " already exists");
        }
        return registry;
    }

    /**
     * Finds the registry another mod created.
     *
     * @return the registry, or empty when none has this id or its entries are not of {@code type}
     */
    @SuppressWarnings("unchecked")
    public static <T> Optional<ModRegistry<T>> find(ResourceId id, Class<T> type) {
        ModRegistry<?> found = BY_ID.get(id);
        if (found == null || !found.type.equals(type)) {
            return Optional.empty();
        }
        return Optional.of((ModRegistry<T>) found);
    }

    /** The registry's name. */
    public ResourceId id() {
        return id;
    }

    /** The entry type. */
    public Class<T> type() {
        return type;
    }

    /**
     * Adds an entry.
     *
     * @return {@code value}, for chaining
     * @throws IllegalStateException when the registry is frozen
     * @throws IllegalArgumentException when the id or the value (the same object) is already registered
     */
    public T register(ResourceId key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        synchronized (this) {
            if (frozen) {
                throw new IllegalStateException("Registry " + id + " is frozen; cannot register " + key);
            }
            if (entries.containsKey(key)) {
                throw new IllegalArgumentException("Registry " + id + " already has " + key);
            }
            if (ids.containsKey(value)) {
                throw new IllegalArgumentException(
                        "Registry " + id + " already holds this object as " + ids.get(value));
            }
            entries.put(key, value);
            ids.put(value, key);
            byRawId.add(value);
        }
        return value;
    }

    /** Runs {@code listener} once when the registry freezes, or at once when it is frozen already. */
    public void onFreeze(Consumer<ModRegistry<T>> listener) {
        Objects.requireNonNull(listener, "listener");
        boolean now;
        synchronized (this) {
            now = frozen;
            if (!now) {
                freezeListeners.add(listener);
            }
        }
        if (now) {
            listener.accept(this);
        }
    }

    /** Closes the registry to new entries and runs the freeze listeners. Calling it again does nothing. */
    public void freeze() {
        List<Consumer<ModRegistry<T>>> toRun;
        synchronized (this) {
            if (frozen) {
                return;
            }
            frozen = true;
            toRun = List.copyOf(freezeListeners);
            freezeListeners.clear();
        }
        toRun.forEach(listener -> listener.accept(this));
    }

    /** Whether {@link #freeze()} was called. */
    public synchronized boolean isFrozen() {
        return frozen;
    }

    /** The entry with this id. */
    public synchronized Optional<T> get(ResourceId key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * The entry with this id.
     *
     * @throws NoSuchElementException when there is none
     */
    public T require(ResourceId key) {
        return get(key).orElseThrow(() -> new NoSuchElementException("Registry " + id + " has no " + key));
    }

    /** Whether the registry has an entry with this id. */
    public synchronized boolean contains(ResourceId key) {
        return entries.containsKey(key);
    }

    /** The id the object was registered under (compared by identity). */
    public synchronized Optional<ResourceId> idOf(T value) {
        return Optional.ofNullable(ids.get(value));
    }

    /** The raw id (position) of the entry, or -1 when there is none. */
    public synchronized int rawId(ResourceId key) {
        T value = entries.get(key);
        if (value == null) {
            return -1;
        }
        for (int i = 0; i < byRawId.size(); i++) {
            if (byRawId.get(i) == value) {
                return i;
            }
        }
        return -1;
    }

    /** The entry at this raw id, or empty when the number is out of range. */
    public synchronized Optional<T> byRawId(int rawId) {
        return rawId >= 0 && rawId < byRawId.size() ? Optional.of(byRawId.get(rawId)) : Optional.empty();
    }

    /** The ids of all entries in registration order (a copy). */
    public synchronized List<ResourceId> keys() {
        return List.copyOf(entries.keySet());
    }

    /** All entries in registration order (a copy). */
    public synchronized List<T> values() {
        return Collections.unmodifiableList(new ArrayList<>(byRawId));
    }

    /** The number of entries. */
    public synchronized int size() {
        return byRawId.size();
    }
}
