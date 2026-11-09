package dev.plexusmodloader.game.version;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;

/**
 * Where an adapter's built-in rules call the mod initialization {@link GameVersionAdapter#bindModInitialization} binds:
 * in {@code targetClass}'s method {@code method}, the call {@code call}, in Mixin's target notation ({@code
 * Ldev/plexusmodloader/game/version/v1_20_1/Minecraft1_20_1Hooks;onModInitialization()V}). Fabric Loader and Quilt
 * Loader call their own {@code Hooks.startClient} and {@code Hooks.startServer} at these places; the Fabric bridge
 * moves other loaders' injections next to those calls here.
 *
 * @param side the game that makes the call: {@link GameSide#CLIENT} in the client's constructor, {@link
 *     GameSide#DEDICATED_SERVER} in the dedicated server's {@code main}
 * @param targetClass the class, internal name ({@code net/minecraft/client/Minecraft})
 * @param method the method's name ({@code <init>}, {@code main})
 * @param call the inserted call
 */
@ApiStability(StabilityLevel.INTERNAL)
public record ModInitializationCall(GameSide side, String targetClass, String method, String call) {

    public ModInitializationCall {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(targetClass, "targetClass");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(call, "call");
    }
}
