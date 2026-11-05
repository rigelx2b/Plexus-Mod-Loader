package dev.plexusmodloader.api.entity;

import dev.plexusmodloader.api.content.attribute.AttributeOperation;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.OptionalDouble;

/** An entity with health and status effects: players and mobs. Same rules as {@link EntityView}. */
@ApiStability(StabilityLevel.PREVIEW)
public interface LivingEntityView extends EntityView {

    /** Current health in half-hearts. */
    float health();

    /** Maximum health. */
    float maxHealth();

    /** Sets health, clamped to {@code [0, maxHealth]}; server side only. */
    void setHealth(float health);

    /**
     * Deals generic damage on the server, as if from {@code /damage}. Armour and
     * {@link dev.plexusmodloader.api.event.entity.EntityEvents#ALLOW_DAMAGE} apply.
     *
     * @return whether the entity was hurt
     * @throws IllegalStateException on the client side
     */
    boolean hurt(float amount);

    /**
     * Deals damage of a data-defined damage type, such as {@code minecraft:magic} or one from the
     * mod's {@code data/<mod id>/damage_type/}.
     *
     * @throws IllegalArgumentException for an unknown damage type
     * @throws IllegalStateException on the client side
     */
    boolean hurt(ResourceId damageType, float amount);

    /**
     * Adds a status effect, such as {@code minecraft:speed} or a mod effect.
     *
     * @param ticks duration in ticks (20 per second)
     * @param amplifier 0 for level I
     * @throws IllegalArgumentException for an unknown effect
     * @throws IllegalStateException on the client side
     */
    void addEffect(ResourceId effect, int ticks, int amplifier);

    /** Whether the entity has the effect. */
    boolean hasEffect(ResourceId effect);

    /**
     * The current value of an attribute, such as {@code minecraft:armor} or a mod attribute, after modifiers. On the
     * client, the value of a mod attribute is the server's only when it is {@code syncedToClients}; otherwise the client
     * entity still carries the attribute and reports its default value, which says nothing about the server's.
     *
     * @return the value, or empty when this entity does not carry the attribute
     * @throws IllegalArgumentException for an unknown attribute
     */
    OptionalDouble attribute(ResourceId attribute);

    /**
     * The base value of an attribute, before modifiers.
     *
     * @return the base value, or empty when this entity does not carry the attribute
     * @throws IllegalArgumentException for an unknown attribute
     */
    OptionalDouble attributeBase(ResourceId attribute);

    /**
     * Sets the base value of an attribute; the game keeps the value inside the attribute's range. Saved with the
     * entity.
     *
     * @throws IllegalArgumentException for an unknown attribute, or one this entity does not carry
     * @throws IllegalStateException on the client side
     */
    void setAttributeBase(ResourceId attribute, double value);

    /**
     * Adds a modifier to an attribute, replacing one with the same {@code modifier} ID.
     *
     * @param modifier the modifier's ID, in the mod's namespace by convention
     * @param persistent whether the modifier is saved with the entity; a non-persistent one ends when the entity is
     *     unloaded
     * @throws IllegalArgumentException for an unknown attribute, or one this entity does not carry
     * @throws IllegalStateException on the client side
     */
    void addAttributeModifier(
            ResourceId attribute, ResourceId modifier, double amount, AttributeOperation operation, boolean persistent);

    /**
     * Removes a modifier from an attribute.
     *
     * @return whether the entity had it
     * @throws IllegalArgumentException for an unknown attribute
     * @throws IllegalStateException on the client side
     */
    boolean removeAttributeModifier(ResourceId attribute, ResourceId modifier);

    /**
     * Launches a projectile, such as a mod projectile or {@code minecraft:snowball}, from the eyes in
     * the look direction.
     *
     * @param speed blocks per tick (snowball 1.5)
     * @param inaccuracy random spread (players 1.0, 0 for none)
     * @return the launched projectile
     * @throws IllegalArgumentException when {@code type} is not a projectile
     * @throws IllegalStateException on the client side
     */
    EntityView launchProjectile(ResourceId type, float speed, float inaccuracy);
}
