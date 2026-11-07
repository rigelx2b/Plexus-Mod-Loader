package dev.plexusmodloader.game.layer.v26_1;

import dev.plexusmodloader.api.entity.LivingEntityView;
import dev.plexusmodloader.api.event.entity.EntityEvents;
import dev.plexusmodloader.api.event.entity.EquipmentSlotName;
import dev.plexusmodloader.api.inventory.ItemStackView;
import dev.plexusmodloader.game.version.layer.GameLayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Hook for {@link EntityEvents#EQUIPMENT_CHANGED}. The transformation rule is in the version adapter; the target {@code
 * LivingEntity.onEquipItem(EquipmentSlot, ItemStack, ItemStack)} is public with the same descriptor in every supported
 * version (verified with {@code javap}) and is where the game reacts to a slot change.
 */
public final class EquipmentHooks {

    private EquipmentHooks() {}

    /** Head of {@code LivingEntity.onEquipItem(slot, from, to)}: the slot already holds {@code to}. */
    public static void onEquipItem(LivingEntity entity, EquipmentSlot slot, ItemStack from, ItemStack to) {
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null
                || entity.level().isClientSide()
                || !context.events().hasListeners(EntityEvents.EQUIPMENT_CHANGED)
                || ItemStack.matches(from, to)) {
            return;
        }
        EquipmentSlotName name;
        try {
            name = EquipmentSlotName.valueOf(slot.name());
        } catch (IllegalArgumentException unknown) {
            return;
        }
        LivingEntityView view = (LivingEntityView) Views.entity(entity);
        ItemStackView before = view(from);
        ItemStackView after = view(to);
        context.events()
                .publish(EntityEvents.EQUIPMENT_CHANGED, listener -> listener.onChange(view, name, before, after));
    }

    private static ItemStackView view(ItemStack stack) {
        return stack.isEmpty()
                ? ItemStackView.EMPTY
                : new ItemStackView(Views.id(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount());
    }
}
