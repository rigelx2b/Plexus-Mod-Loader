package dev.plexusmodloader.game.layer.v1_20_1.client;

import dev.plexusmodloader.api.content.block.entity.MenuLayout;
import dev.plexusmodloader.game.layer.v1_20_1.Views;
import dev.plexusmodloader.game.layer.v1_20_1.content.PlexusMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The screen of a {@link PlexusMenu}: the layout's background texture, or a plain panel with slot
 * frames, plus progress bars drawn with the game's furnace sprites.
 */
final class PlexusContainerScreen extends AbstractContainerScreen<PlexusMenu> {

    /** 1.20.1 has no GUI sprite atlas (1.20.2 added it): the slot frame is cut from the chest texture. */
    private static final ResourceLocation SLOT = new ResourceLocation("textures/gui/container/generic_54.png");

    private static final ResourceLocation FURNACE = new ResourceLocation("textures/gui/container/furnace.png");

    private final MenuLayout layout;

    PlexusContainerScreen(PlexusMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.layout = menu.layout();
        // The constructor takes no size in 1.20.1; the fields are set after it.
        this.imageWidth = layout.width();
        this.imageHeight = layout.height();
        this.inventoryLabelX = layout.inventoryX();
        this.inventoryLabelY = layout.inventoryY() - 11;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // Screen.render has drawn the dimmed background before calling this.
        int x = leftPos;
        int y = topPos;
        if (layout.background().isPresent()) {
            graphics.blit(Views.id(layout.background().get()), x, y, 0, 0, imageWidth, imageHeight, 256, 256);
        } else {
            panel(graphics, x, y, imageWidth, imageHeight);
            for (Slot slot : menu.slots) {
                graphics.blit(SLOT, x + slot.x - 1, y + slot.y - 1, 7, 17, 18, 18, 256, 256);
            }
        }
        for (int index = 0; index < layout.progressBars().size(); index++) {
            MenuLayout.ProgressBar bar = layout.progressBars().get(index);
            float progress = menu.progress(index);
            int bx = x + bar.x();
            int by = y + bar.y();
            switch (bar.style()) {
                case ARROW -> {
                    graphics.blit(FURNACE, bx, by, 79, 34, 24, 16, 256, 256);
                    int width = Mth.ceil(progress * 24.0f);
                    if (width > 0) {
                        graphics.blit(FURNACE, bx, by, 176, 14, width, 16, 256, 256);
                    }
                }
                case FLAME -> {
                    graphics.blit(FURNACE, bx, by, 56, 36, 14, 14, 256, 256);
                    int height = Mth.ceil(progress * 13.0f) + (progress > 0 ? 1 : 0);
                    if (height > 0) {
                        graphics.blit(FURNACE, bx, by + 14 - height, 176, 14 - height, 14, height, 256, 256);
                    }
                }
            }
        }
    }

    /** The game's raised grey panel: dark outline, light top-left edge, dark bottom-right edge. */
    private static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 1, y, x + width - 1, y + height, 0xFF000000);
        graphics.fill(x, y + 1, x + width, y + height - 1, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFFFFFFFF);
        graphics.fill(x + 3, y + 3, x + width - 1, y + height - 1, 0xFF555555);
        graphics.fill(x + 3, y + 3, x + width - 3, y + height - 3, 0xFFC6C6C6);
    }
}
