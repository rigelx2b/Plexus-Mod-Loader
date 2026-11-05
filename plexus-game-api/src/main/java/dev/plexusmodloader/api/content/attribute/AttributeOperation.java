package dev.plexusmodloader.api.content.attribute;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/**
 * How an attribute modifier changes the value. The game applies every {@link #ADD_VALUE} to the base first, then
 * {@link #ADD_MULTIPLIED_BASE}, then {@link #ADD_MULTIPLIED_TOTAL}, and keeps the result inside the attribute's range.
 */
@ApiStability(StabilityLevel.PREVIEW)
public enum AttributeOperation {
    /** Adds the amount to the base. */
    ADD_VALUE,
    /** Adds {@code base * amount}, using the base after every {@link #ADD_VALUE}. */
    ADD_MULTIPLIED_BASE,
    /** Multiplies the running value by {@code 1 + amount}. */
    ADD_MULTIPLIED_TOTAL
}
