package dev.plexusmodloader.api.event.entity;

import dev.plexusmodloader.api.entity.LivingEntityView;
import dev.plexusmodloader.api.inventory.ItemStackView;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link EntityEvents#EQUIPMENT_CHANGED}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface EquipmentChangeListener {

    /**
     * Called on the server thread after an entity's equipment in {@code slot} changed from one stack to another. The
     * view is valid only during the call.
     *
     * @param entity the entity that now wears or holds {@code to}
     * @param slot the slot that changed
     * @param from what the slot held before; {@link ItemStackView#EMPTY} when it was empty
     * @param to what the slot holds now; {@link ItemStackView#EMPTY} when it was emptied
     */
    void onChange(LivingEntityView entity, EquipmentSlotName slot, ItemStackView from, ItemStackView to);
}
