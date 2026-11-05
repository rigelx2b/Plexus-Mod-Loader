package dev.plexusmodloader.game.layer.v1_21_1.client;

import dev.plexusmodloader.api.content.block.entity.MenuLayout;
import dev.plexusmodloader.game.layer.v1_21_1.Views;
import dev.plexusmodloader.game.layer.v1_21_1.content.PlexusMenu;
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

    private static final ResourceLocation SLOT = ResourceLocation.withDefaultNamespace("container/slot");
    private static final ResourceLocation FURNACE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/furnace.png");
    private static final ResourceLocation ARROW =
            ResourceLocation.withDefaultNamespace("container/furnace/burn_progress");
    private static final ResourceLocation FLAME =
            ResourceLocation.withDefaultNamespace("container/furnace/lit_progress");

    private final MenuLayout layout;

    PlexusContainerScreen(PlexusMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.layout = menu.layout();
        // 1.21.1 has no constructor that takes the size.
        this.imageWidth = layout.width();
        this.imageHeight = layout.height();
        this.inventoryLabelX = layout.inventoryX();
        this.inventoryLabelY = layout.inventoryY() - 11;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // Screen.renderBackground has drawn the dimmed background before calling this.
        int x = leftPos;
        int y = topPos;
        if (layout.background().isPresent()) {
            graphics.blit(Views.id(layout.background().get()), x, y, 0.0f, 0.0f, imageWidth, imageHeight, 256, 256);
        } else {
            panel(graphics, x, y, imageWidth, imageHeight);
            for (Slot slot : menu.slots) {
                graphics.blitSprite(SLOT, x + slot.x - 1, y + slot.y - 1, 18, 18);
            }
        }
        for (int index = 0; index < layout.progressBars().size(); index++) {
            MenuLayout.ProgressBar bar = layout.progressBars().get(index);
            float progress = menu.progress(index);
            int bx = x + bar.x();
            int by = y + bar.y();
            switch (bar.style()) {
                case ARROW -> {
                    graphics.blit(FURNACE, bx, by, 79.0f, 34.0f, 24, 16, 256, 256);
                    int width = Mth.ceil(progress * 24.0f);
                    if (width > 0) {
                        graphics.blitSprite(ARROW, 24, 16, 0, 0, bx, by, width, 16);
                    }
                }
                case FLAME -> {
                    graphics.blit(FURNACE, bx, by, 56.0f, 36.0f, 14, 14, 256, 256);
                    int height = Mth.ceil(progress * 13.0f) + (progress > 0 ? 1 : 0);
                    if (height > 0) {
                        graphics.blitSprite(FLAME, 14, 14, 0, 14 - height, bx, by + 14 - height, 14, height);
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
