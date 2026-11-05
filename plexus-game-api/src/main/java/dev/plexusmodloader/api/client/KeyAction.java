package dev.plexusmodloader.api.client;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** What happened to a key or mouse button. The game layer converts each version's own values. */
@ApiStability(StabilityLevel.PREVIEW)
public enum KeyAction {
    /** Pressed down. */
    PRESS,
    /** Released. */
    RELEASE,
    /** Held long enough that the system repeats it (keys only). */
    REPEAT
}
