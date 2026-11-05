package dev.plexusmodloader.game.layer.v1_21_1.content;

import java.util.function.Supplier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;

/** Maps Native API interaction results and hands onto the game's and back. */
public final class Interactions {

    private Interactions() {}

    /** The game result for {@code result}; {@code PASS} falls back to the game's default behaviour. */
    public static InteractionResult toGame(
            dev.plexusmodloader.api.world.InteractionResult result, Supplier<InteractionResult> gameDefault) {
        return switch (result) {
            case SUCCESS -> InteractionResult.SUCCESS;
            case CONSUME -> InteractionResult.CONSUME;
            case FAIL -> InteractionResult.FAIL;
            case PASS -> gameDefault.get();
        };
    }

    /**
     * The Native API result for a game result: 1.21.1 returns no difference between a success that swings
     * the arm and one that does not on the server, so every used action is {@code SUCCESS}; "try with empty hand" is
     * {@code PASS}.
     */
    public static dev.plexusmodloader.api.world.InteractionResult fromGame(InteractionResult result) {
        // 1.21.1 results are an enum: SUCCESS, CONSUME, CONSUME_PARTIAL, PASS, FAIL.
        return switch (result) {
            // The server answers a handled click with CONSUME (InteractionResult.sidedSuccess: SUCCESS is the client's
            // answer, which swings the arm), so every result that used the action is the Native API's SUCCESS.
            case SUCCESS, SUCCESS_NO_ITEM_USED, CONSUME, CONSUME_PARTIAL ->
                dev.plexusmodloader.api.world.InteractionResult.SUCCESS;
            case FAIL -> dev.plexusmodloader.api.world.InteractionResult.FAIL;
            case PASS -> dev.plexusmodloader.api.world.InteractionResult.PASS;
        };
    }

    /**
     * The game result of {@code Item.use} for {@code result}, which 1.21.1 returns together with the (possibly
     * replaced) stack; {@code PASS} falls back to the game's default behaviour.
     */
    public static InteractionResultHolder<ItemStack> toGameHolder(
            dev.plexusmodloader.api.world.InteractionResult result,
            ItemStack stack,
            Supplier<InteractionResultHolder<ItemStack>> gameDefault) {
        return switch (result) {
            case SUCCESS -> InteractionResultHolder.success(stack);
            case CONSUME -> InteractionResultHolder.consume(stack);
            case FAIL -> InteractionResultHolder.fail(stack);
            case PASS -> gameDefault.get();
        };
    }

    /** The Native API hand. */
    public static dev.plexusmodloader.api.entity.Hand hand(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND
                ? dev.plexusmodloader.api.entity.Hand.MAIN_HAND
                : dev.plexusmodloader.api.entity.Hand.OFF_HAND;
    }
}
