package dev.plexusmodloader.game.version.layer;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import dev.plexusmodloader.game.version.EventPublisher;
import dev.plexusmodloader.game.version.GameLaunchTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What the runtime hands to the {@link GameLayer}.
 *
 * @param side client or dedicated server (the game test server runs as a dedicated server)
 * @param target the game entry point being started
 * @param events fires events to mod listeners
 * @param resourcePacks loaded mods whose jars Plexus Mod Loader exposes as resource and data packs, in load order
 * @param declarations everything mods declared through the Native API
 * @param modCounts how many mods each loader loaded, for the title screen ({@link ModCountLines})
 * @param gameWindow told when the game opens its window and shows its loading screen
 * @param foreignResourcePacks other loaders' mods whose jars Plexus Mod Loader also exposes as resource and data packs,
 *     in load order, because the Fabric API build that serves them does not make their packs (a host's build next to
 *     NeoForge 21.1, ADR-0043); not part of {@code resourcePacks}, which stays the Plexus Mods
 */
@ApiStability(StabilityLevel.INTERNAL)
public record GameLayerContext(
        GameSide side,
        GameLaunchTarget target,
        EventPublisher events,
        List<ModResourcePack> resourcePacks,
        NativeDeclarations declarations,
        LoadedModCounts modCounts,
        GameWindowListener gameWindow,
        List<ModResourcePack> foreignResourcePacks) {

    public GameLayerContext {
        resourcePacks = List.copyOf(resourcePacks);
        foreignResourcePacks = List.copyOf(foreignResourcePacks);
        Objects.requireNonNull(modCounts, "modCounts");
        Objects.requireNonNull(gameWindow, "gameWindow");
    }

    /** A context without other loaders' mods to expose as packs. */
    public GameLayerContext(
            GameSide side,
            GameLaunchTarget target,
            EventPublisher events,
            List<ModResourcePack> resourcePacks,
            NativeDeclarations declarations,
            LoadedModCounts modCounts,
            GameWindowListener gameWindow) {
        this(side, target, events, resourcePacks, declarations, modCounts, gameWindow, List.of());
    }

    /**
     * Every mod Plexus Mod Loader exposes as a resource and data pack, in this order: the Plexus Mods ({@link
     * #resourcePacks}), then {@link #foreignResourcePacks}.
     */
    public List<ModResourcePack> modPacks() {
        if (foreignResourcePacks.isEmpty()) {
            return resourcePacks;
        }
        List<ModResourcePack> all = new ArrayList<>(resourcePacks);
        all.addAll(foreignResourcePacks);
        return List.copyOf(all);
    }

    /** A context whose game window tells nothing ({@link GameWindowListener#NONE}). */
    public GameLayerContext(
            GameSide side,
            GameLaunchTarget target,
            EventPublisher events,
            List<ModResourcePack> resourcePacks,
            NativeDeclarations declarations,
            LoadedModCounts modCounts) {
        this(side, target, events, resourcePacks, declarations, modCounts, GameWindowListener.NONE);
    }
}
