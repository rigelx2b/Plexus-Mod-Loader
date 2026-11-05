package dev.plexusmodloader.api.client;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/**
 * Plexus Mod Loader mouse button numbers, the GLFW numbers on every Minecraft version (as {@link KeyCodes}, ADR-0029):
 * the game layer converts them, so a mod gets the same number on 26.3, whose game numbers buttons from 1 the SDL way.
 * Extra buttons follow from 3.
 */
@ApiStability(StabilityLevel.PREVIEW)
public final class MouseButtons {

    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int MIDDLE = 2;

    private MouseButtons() {}
}
