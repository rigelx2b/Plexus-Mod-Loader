package dev.plexusmodloader.game.layer.v1_20_4.content;

import dev.plexusmodloader.api.content.item.ItemBehavior;
import dev.plexusmodloader.api.content.item.ItemUse;
import dev.plexusmodloader.game.layer.v1_20_4.Views;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** A mod item whose use forwards to the mod's {@link ItemBehavior}. */
final class PlexusItem extends Item {

    private final ItemBehavior behavior;

    PlexusItem(Properties properties, ItemBehavior behavior) {
        super(properties);
        this.behavior = behavior;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        return Interactions.toGameHolder(
                behavior.onUse(
                        new ItemUse(Views.world(level), Views.player(player), ItemComponents.ref(stack, () -> {}))),
                stack,
                () -> super.use(level, player, hand));
    }
}
