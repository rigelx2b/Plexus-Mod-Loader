package dev.plexusmodloader.api.content.attribute;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;

/**
 * A mod attribute: a number every entity in {@code scope} carries, which modifiers raise or lower, like the game's
 * {@code minecraft:max_health} or {@code minecraft:luck}. Its name comes from {@code attribute.name.<mod id>.<path>}.
 * Read and change it with {@code LivingEntityView.attribute}, {@code setAttributeBase} and {@code
 * addAttributeModifier}, or with the game's {@code /attribute} command.
 *
 * <p>The game keeps the value inside {@code [minValue, maxValue]} after modifiers. Entities save their base value and
 * permanent modifiers with the world.
 *
 * @param defaultValue base value of an entity that never changed it
 * @param minValue lowest value
 * @param maxValue highest value
 * @param syncedToClients whether clients learn the value of the entities they see (needed only when client code reads
 *     it)
 * @param scope which entities carry it
 */
@ApiStability(StabilityLevel.PREVIEW)
public record AttributeDefinition(
        double defaultValue, double minValue, double maxValue, boolean syncedToClients, AttributeScope scope) {

    /**
     * @throws IllegalArgumentException when a value is not finite, {@code minValue > maxValue}, or the default is
     *     outside the range
     */
    public AttributeDefinition {
        Objects.requireNonNull(scope, "scope");
        if (!Double.isFinite(defaultValue) || !Double.isFinite(minValue) || !Double.isFinite(maxValue)) {
            throw new IllegalArgumentException(
                    "attribute values must be finite: " + defaultValue + ", " + minValue + ", " + maxValue);
        }
        if (minValue > maxValue) {
            throw new IllegalArgumentException("minValue " + minValue + " is greater than maxValue " + maxValue);
        }
        if (defaultValue < minValue || defaultValue > maxValue) {
            throw new IllegalArgumentException(
                    "defaultValue " + defaultValue + " is outside [" + minValue + ", " + maxValue + "]");
        }
    }

    /** An attribute every living entity carries, not synchronised to clients. */
    public static AttributeDefinition of(double defaultValue, double minValue, double maxValue) {
        return new AttributeDefinition(defaultValue, minValue, maxValue, false, AttributeScope.LIVING_ENTITIES);
    }

    /** The same definition, synchronised to clients. */
    public AttributeDefinition withClientSync() {
        return new AttributeDefinition(defaultValue, minValue, maxValue, true, scope);
    }

    /** The same definition, carried only by {@code newScope}. */
    public AttributeDefinition withScope(AttributeScope newScope) {
        return new AttributeDefinition(defaultValue, minValue, maxValue, syncedToClients, newScope);
    }
}
