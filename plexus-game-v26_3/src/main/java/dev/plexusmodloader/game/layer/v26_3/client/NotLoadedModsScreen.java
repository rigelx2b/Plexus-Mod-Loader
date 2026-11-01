package dev.plexusmodloader.game.layer.v26_3.client;

import dev.plexusmodloader.game.layer.v26_3.LayerState;
import dev.plexusmodloader.game.version.layer.GameLayerContext;
import dev.plexusmodloader.game.version.layer.LoadedModCounts;
import dev.plexusmodloader.game.version.layer.NotLoadedModsNotice;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The notice of mods that are not loaded ({@link NotLoadedModsNotice}), over the title screen the first time it shows
 * after the game loaded, until the player presses its button or Esc; then the title screen again. The loading screens
 * name those mods too, but they are gone when loading ends. Opened from the client tick ({@link #onClientTick}), under
 * every loader, since the hosts patch the title screen itself.
 *
 * <p>Thread: render thread.
 */
final class NotLoadedModsScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");
    private static final AtomicBoolean SHOWN = new AtomicBoolean();
    private static final int TEXT_COLOUR = 0xFFFFFFFF;
    private static final int TITLE_COLOUR = 0xFFFFE08A;
    private static final int LINE_STEP = 10;
    private static final int PARAGRAPH_GAP = 6;

    private final NotLoadedModsNotice notice;
    private final Screen previous;
    private final List<List<FormattedCharSequence>> paragraphs = new ArrayList<>();
    private int top;

    private NotLoadedModsScreen(NotLoadedModsNotice notice, Screen previous) {
        super(Component.literal(notice.title()));
        this.notice = notice;
        this.previous = previous;
    }

    /**
     * After each client tick: once loading is over and the title screen shows, opens the notice over it, once per
     * launch, when mods are not loaded.
     */
    static void onClientTick(Minecraft minecraft) {
        if (SHOWN.get() || minecraft.gui.overlay() != null || !(minecraft.gui.screen() instanceof TitleScreen title)) {
            return;
        }
        LoadedModCounts counts =
                LayerState.context().map(GameLayerContext::modCounts).orElse(LoadedModCounts.NONE);
        Optional<NotLoadedModsNotice> notice = NotLoadedModsNotice.of(counts, TitleScreenModCounts.translations());
        if (SHOWN.compareAndSet(false, true) && notice.isPresent()) {
            minecraft.gui.setScreen(new NotLoadedModsScreen(notice.get(), title));
            LOGGER.info(
                    "[Plexus Mod Loader] Notice of mods not loaded shown over the title screen: {} | {}",
                    notice.get().title(),
                    String.join(" | ", notice.get().paragraphs()));
        }
    }

    @Override
    protected void init() {
        int textWidth = Math.min(width - 40, 360);
        paragraphs.clear();
        int height = font.lineHeight + PARAGRAPH_GAP * 2;
        for (String paragraph : notice.paragraphs()) {
            List<FormattedCharSequence> lines = font.split(Component.literal(paragraph), textWidth);
            paragraphs.add(lines);
            height += lines.size() * LINE_STEP + PARAGRAPH_GAP;
        }
        height += 20;
        top = Math.max(10, (this.height - height) / 2);
        addRenderableWidget(Button.builder(CommonComponents.GUI_OK, button -> onClose())
                .bounds(width / 2 - 100, top + height - 20, 200, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int y = top;
        graphics.centeredText(font, title, width / 2, y, TITLE_COLOUR);
        y += font.lineHeight + PARAGRAPH_GAP * 2;
        for (List<FormattedCharSequence> lines : paragraphs) {
            for (FormattedCharSequence line : lines) {
                graphics.text(font, line, (width - font.width(line)) / 2, y, TEXT_COLOUR, true);
                y += LINE_STEP;
            }
            y += PARAGRAPH_GAP;
        }
    }

    @Override
    public void onClose() {
        LOGGER.info("[Plexus Mod Loader] Notice of mods not loaded closed by the player");
        minecraft.gui.setScreen(previous);
    }
}
