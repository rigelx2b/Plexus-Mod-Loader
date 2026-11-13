package dev.plexusmodloader.game.layer.v1_21_1;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.game.layer.v1_21_1.client.ModInitializationPoint;
import dev.plexusmodloader.game.layer.v1_21_1.client.TitleScreenModCounts;
import dev.plexusmodloader.game.version.layer.GameLayer;
import dev.plexusmodloader.game.version.layer.GameLayerContext;
import java.util.List;

/**
 * Entry point of the Minecraft 1.21.1 game layer. Loaded by the game class loader (ADR-0010); the
 * transformation rules of {@code Minecraft1_21_1Adapter} call {@link GameHooks} and
 * {@code client.ClientHooks}, which use the state installed here.
 */
public final class GameLayer1_21_1 implements GameLayer {

    @Override
    public void start(GameLayerContext context) {
        LayerState.install(context);
    }

    /** The title screen's mod counts per loader ({@link TitleScreenModCounts}), on the client. */
    @Override
    public List<String> brandingLines() {
        boolean client = LayerState.context()
                .map(context -> context.side() == GameSide.CLIENT)
                .orElse(false);
        return client ? TitleScreenModCounts.lines() : List.of();
    }

    /** On the client, without {@code Minecraft}'s game thread, as at its mod initialization point ({@link ModInitializationPoint}). */
    @Override
    public void runAsAtModInitializationPoint(Runnable modInitialization) {
        boolean client = LayerState.context()
                .map(context -> context.side() == GameSide.CLIENT)
                .orElse(false);
        if (client) {
            ModInitializationPoint.runWithoutGameThread(modInitialization);
        } else {
            modInitialization.run();
        }
    }
}
