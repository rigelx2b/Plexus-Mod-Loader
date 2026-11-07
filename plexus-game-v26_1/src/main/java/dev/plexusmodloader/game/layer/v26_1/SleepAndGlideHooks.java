package dev.plexusmodloader.game.layer.v26_1;

import com.mojang.datafixers.util.Either;
import dev.plexusmodloader.api.entity.LivingEntityView;
import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.event.EventResult;
import dev.plexusmodloader.api.event.entity.EntityEvents;
import dev.plexusmodloader.api.event.player.PlayerEvents;
import dev.plexusmodloader.game.version.layer.GameLayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Hooks for the sleep events ({@link PlayerEvents#ALLOW_SLEEPING}, {@link EntityEvents#STARTED_SLEEPING}, {@link
 * EntityEvents#STOPPING_SLEEP}) and {@link PlayerEvents#ALLOW_FALL_FLYING}. The transformation rules are in {@code
 * Minecraft26_1Adapter}; each parameter list mirrors its target (verified with {@code javap} against the 26.1 client
 * jar).
 */
public final class SleepAndGlideHooks {

    private SleepAndGlideHooks() {}

    /**
     * Head of {@code ServerPlayer.startSleepInBed}: a non-null result is what the game returns, so the player does not
     * lie down. {@code OTHER_PROBLEM} is the game's problem without a chat message.
     */
    public static Either<Player.BedSleepingProblem, Unit> onStartSleepInBed(ServerPlayer player, BlockPos pos) {
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null || !context.events().hasListeners(PlayerEvents.ALLOW_SLEEPING)) {
            return null;
        }
        PlayerView view = Views.player(player);
        EventResult result = context.events()
                .publishUntilDecided(
                        PlayerEvents.ALLOW_SLEEPING,
                        listener -> listener.allowSleeping(view, Views.pos(pos)),
                        EventResult.PASS);
        return result == EventResult.DENY ? Either.left(Player.BedSleepingProblem.OTHER_PROBLEM) : null;
    }

    /** Before the return of {@code LivingEntity.startSleeping(BlockPos)}: the entity is asleep. */
    public static void onStartSleeping(LivingEntity entity, BlockPos pos) {
        fireStarted(entity, pos);
    }

    private static void fireStarted(LivingEntity entity, BlockPos pos) {
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null
                || entity.level().isClientSide()
                || !context.events().hasListeners(EntityEvents.STARTED_SLEEPING)) {
            return;
        }
        LivingEntityView view = (LivingEntityView) Views.entity(entity);
        context.events().publish(EntityEvents.STARTED_SLEEPING, listener -> listener.onSleep(view, Views.pos(pos)));
    }

    /** Head of {@code LivingEntity.stopSleeping()}: the entity is still asleep. */
    public static void onStopSleeping(LivingEntity entity) {
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null
                || entity.level().isClientSide()
                || !context.events().hasListeners(EntityEvents.STOPPING_SLEEP)) {
            return;
        }
        entity.getSleepingPos().ifPresent(pos -> {
            LivingEntityView view = (LivingEntityView) Views.entity(entity);
            context.events().publish(EntityEvents.STOPPING_SLEEP, listener -> listener.onSleep(view, Views.pos(pos)));
        });
    }

    /**
     * Before the return of {@code Player.tryToStartFallFlying()}: the game has checked the elytra and started the glide
     * ({@code returned}); a denying listener ends it again.
     */
    public static boolean onTryToStartFallFlying(Player player, boolean returned) {
        if (!returned || player.level().isClientSide()) {
            return returned;
        }
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null || !context.events().hasListeners(PlayerEvents.ALLOW_FALL_FLYING)) {
            return true;
        }
        PlayerView view = Views.player(player);
        EventResult result = context.events()
                .publishUntilDecided(
                        PlayerEvents.ALLOW_FALL_FLYING, listener -> listener.allowFallFlying(view), EventResult.PASS);
        if (result == EventResult.DENY) {
            player.stopFallFlying();
            return false;
        }
        return true;
    }
}
