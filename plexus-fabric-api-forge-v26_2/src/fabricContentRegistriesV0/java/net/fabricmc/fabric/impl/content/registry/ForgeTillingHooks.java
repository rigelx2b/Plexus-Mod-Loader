package net.fabricmc.fabric.impl.content.registry;

import com.mojang.datafixers.util.Pair;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.mixin.content.registry.HoeItemAccessor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.event.level.BlockEvent;

// Plexus Mod Loader (ADR-0031), for MinecraftForge 26.2-65.1.5 (javap): Forge's HoeItem.useOn asks
// BlockState.getToolModifiedState(HOE_TILL) instead of reading HoeItem.TILLABLES, where TillableBlockRegistry puts
// Fabric's entries; Forge's default answers only for vanilla's blocks. BlockToolModificationEvent, which Forge posts
// before that default, runs the entry of a block Fabric registered, as vanilla's HoeItem.useOn does.
public final class ForgeTillingHooks implements ModInitializer {
    private static final Set<Block> FABRIC_TILLABLES = ConcurrentHashMap.newKeySet();

    @Override
    public void onInitialize() {
        BlockEvent.BlockToolModificationEvent.BUS.addListener(ForgeTillingHooks::till);
    }

    /** Called by TillableBlockRegistry for each block it puts into HoeItem.TILLABLES. */
    public static void registered(Block block) {
        FABRIC_TILLABLES.add(block);
    }

    // Returns whether Forge cancels the event: then getToolModifiedState answers null and HoeItem.useOn does nothing
    // more.
    private static boolean till(BlockEvent.BlockToolModificationEvent event) {
        if (event.getToolAction() != ToolActions.HOE_TILL
                || !event.getHeldItemStack().canPerformAction(ToolActions.HOE_TILL)
                || !FABRIC_TILLABLES.contains(event.getState().getBlock())) {
            return false;
        }
        Pair<Predicate<UseOnContext>, Consumer<UseOnContext>> tilling =
                HoeItemAccessor.getTillables().get(event.getState().getBlock());
        UseOnContext context = event.getContext();
        // As in vanilla's HoeItem.useOn: Fabric's entry replaces the block's vanilla one, and does nothing when its
        // predicate refuses.
        if (tilling == null || event.isSimulated() || !tilling.getFirst().test(context)) {
            return true;
        }
        Level level = context.getLevel();
        Player player = context.getPlayer();
        level.playSound(player, context.getClickedPos(), SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (!level.isClientSide()) {
            tilling.getSecond().accept(context);
            if (player != null) {
                context.getItemInHand()
                        .hurtAndBreak(1, player, context.getHand().asEquipmentSlot());
            }
        }
        return true;
    }
}
