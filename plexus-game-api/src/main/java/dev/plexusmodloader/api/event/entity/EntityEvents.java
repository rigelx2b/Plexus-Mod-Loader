package dev.plexusmodloader.api.event.entity;

import dev.plexusmodloader.api.event.EventKey;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Entity events, fired on the server thread. */
@ApiStability(StabilityLevel.PREVIEW)
public final class EntityEvents {

    /**
     * An entity of any kind joins a server world: spawned, loaded with its chunk, or arriving from
     * another dimension. Fabric API: {@code ServerEntityEvents.ENTITY_LOAD}; NeoForge: {@code
     * EntityJoinLevelEvent} (not cancellable here).
     */
    public static final EventKey<EntityJoinedWorldListener> JOINED_WORLD =
            EventKey.of("plexus:entity/joined_world", EntityJoinedWorldListener.class);

    /**
     * An entity of any kind leaves a server world: removed, unloaded with its chunk, or leaving for
     * another dimension. Fired just before the world stops tracking it, so the entity is still
     * readable. Fabric API: {@code ServerEntityEvents.ENTITY_UNLOAD}; NeoForge: {@code
     * EntityLeaveLevelEvent}.
     */
    public static final EventKey<EntityLeftWorldListener> LEFT_WORLD =
            EventKey.of("plexus:entity/left_world", EntityLeftWorldListener.class);

    /** A living entity is about to take damage; any listener returning {@code false} cancels it. */
    public static final EventKey<AllowDamageListener> ALLOW_DAMAGE =
            EventKey.of("plexus:entity/allow_damage", AllowDamageListener.class);

    /**
     * A living entity is about to take damage that no {@link #ALLOW_DAMAGE} listener cancelled;
     * listeners may change the amount. Armour and resistance apply to the result.
     */
    public static final EventKey<DamageModifier> MODIFY_DAMAGE =
            EventKey.of("plexus:entity/modify_damage", DamageModifier.class);

    /**
     * Damage brought a living entity's health to zero and it is about to die; any listener returning
     * {@code false} keeps it alive with 1 health, like a totem of undying without the effects. Fabric
     * API: {@code ServerLivingEntityEvents.ALLOW_DEATH}; NeoForge: cancelling {@code
     * LivingDeathEvent}.
     */
    public static final EventKey<AllowDeathListener> ALLOW_DEATH =
            EventKey.of("plexus:entity/allow_death", AllowDeathListener.class);

    /**
     * A living entity took damage and lost health. Fired after the damage is applied, so the entity may be
     * dead. Fabric API: {@code ServerLivingEntityEvents.AFTER_DAMAGE}; NeoForge: {@code LivingDamageEvent.Post}.
     */
    public static final EventKey<AfterDamageListener> AFTER_DAMAGE =
            EventKey.of("plexus:entity/after_damage", AfterDamageListener.class);

    /**
     * A living entity died and is about to drop its loot and equipment; a decision event: {@code DENY} drops
     * nothing. Fabric API has no event for this (mods use loot table modification); NeoForge: cancelling {@code
     * LivingDropsEvent}. Items cannot be added or changed here; see the roadmap for loot table modification.
     */
    public static final EventKey<EntityDropsListener> DROPS =
            EventKey.of("plexus:entity/drops", EntityDropsListener.class);

    /** A living entity died. */
    public static final EventKey<DeathListener> DIED = EventKey.of("plexus:entity/died", DeathListener.class);

    /**
     * A living entity was killed by another entity. Fabric API: {@code ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY};
     * NeoForge: {@code LivingDeathEvent} with {@code getSource().getEntity()}. The victim's death is final here; to
     * prevent it use {@link #ALLOW_DEATH}.
     */
    public static final EventKey<KilledOtherListener> KILLED_OTHER =
            EventKey.of("plexus:entity/killed_other", KilledOtherListener.class);

    /**
     * A living entity lay down in a bed (or on another block that lets it sleep) and is now asleep. Fired on the server
     * thread for players, villagers, and any other living entity that sleeps. Fabric API: {@code
     * EntitySleepEvents.START_SLEEPING}; NeoForge: {@code PlayerSleepInBedEvent} (players only).
     */
    public static final EventKey<SleepListener> STARTED_SLEEPING =
            EventKey.of("plexus:entity/started_sleeping", SleepListener.class);

    /**
     * A sleeping living entity is about to wake up. Fired on the server thread while the entity is still asleep, so a
     * listener can read where it lies. Fabric API: {@code EntitySleepEvents.STOP_SLEEPING}; NeoForge: {@code
     * PlayerWakeUpEvent}.
     */
    public static final EventKey<SleepListener> STOPPING_SLEEP =
            EventKey.of("plexus:entity/stopping_sleep", SleepListener.class);

    /**
     * A living entity's equipment in one slot changed (since 2.1.0): armor put on or taken off, a different item in a
     * hand, a mob given gear. Fired on the server thread, only when the stack really differs (same item and data is not
     * a change), also while a mob is created with its gear. It tells what happened; it cannot cancel the change. Fabric
     * API: {@code ServerEntityEvents.EQUIPMENT_CHANGE}; NeoForge: {@code LivingEquipmentChangeEvent}.
     */
    public static final EventKey<EquipmentChangeListener> EQUIPMENT_CHANGED =
            EventKey.of("plexus:entity/equipment_changed", EquipmentChangeListener.class);

    private EntityEvents() {}
}
