package dev.plexusmodloader.game.version.layer;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.List;

/**
 * Version-specific code that is compiled against one Minecraft version and loaded by the game class
 * loader, so it can use game classes directly (ADR-0010). It implements the Native API on top of the
 * game: it creates declared content, installs commands, carries payloads, and so on.
 *
 * <p>Implementations need a public no-argument constructor.
 */
@ApiStability(StabilityLevel.INTERNAL)
public interface GameLayer {

    /**
     * Called once on the main thread after mod entrypoints ran and before the game's {@code main}.
     * Must not touch game classes that initialise registries; the layer reacts to the game later
     * through its transformation hooks.
     */
    void start(GameLayerContext context);

    /**
     * The lines Plexus Mod Loader adds to the game's branding text at the bottom left of the title screen ({@link
     * ModCountLines}), in the game's current language, top to bottom. For a loader that draws that text itself
     * (Forge's branding, through the runtime and the loader's host). Called on the render thread, every frame the
     * title screen is drawn, after {@link #start}.
     *
     * @return the lines, empty by default and outside the client
     */
    default List<String> brandingLines() {
        return List.of();
    }

    /**
     * Runs {@code modInitialization} with the game in the state it has at Minecraft's mod initialization point, for a
     * host that runs the initialization later (NeoForge 21.1's client, after its own mod loading in {@code Minecraft}'s
     * constructor). Called on the game's main thread, after {@link #start}, at most once.
     *
     * @param modInitialization Plexus Mod Loader's mod initialization (other loaders' entrypoints, then {@code
     *     MODS_INITIALIZED})
     */
    default void runAsAtModInitializationPoint(Runnable modInitialization) {
        modInitialization.run();
    }
}
