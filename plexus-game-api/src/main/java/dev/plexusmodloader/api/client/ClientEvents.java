package dev.plexusmodloader.api.client;

import dev.plexusmodloader.api.event.EventKey;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Client events; fired on the client thread, only on the client. */
@ApiStability(StabilityLevel.PREVIEW)
public final class ClientEvents {

    /** The client finished a tick (20 per second, also on the title screen). */
    public static final EventKey<ClientTickListener> TICK = EventKey.of("plexus:client/tick", ClientTickListener.class);

    /**
     * The field of view of the next frame, after the game's own changes (sprinting, underwater);
     * called every frame, keep it cheap.
     */
    public static final EventKey<FovModifier> FIELD_OF_VIEW =
            EventKey.of("plexus:client/field_of_view", FovModifier.class);

    /**
     * The client joined a world (single player or a server) and has its player and world; fired once
     * per connection.
     */
    public static final EventKey<ClientConnectionListener> JOINED_WORLD =
            EventKey.of("plexus:client/joined_world", ClientConnectionListener.class);

    /** The client is leaving the world; the player and world are still available. */
    public static final EventKey<ClientConnectionListener> LEFT_WORLD =
            EventKey.of("plexus:client/left_world", ClientConnectionListener.class);

    /**
     * The game built an item's tooltip; listeners may add, insert, or remove lines. Not fired for
     * items whose tooltip the game hides. Fabric API: {@code ItemTooltipCallback}; NeoForge: {@code
     * ItemTooltipEvent}.
     */
    public static final EventKey<ItemTooltipListener> ITEM_TOOLTIP =
            EventKey.of("plexus:client/item_tooltip", ItemTooltipListener.class);

    /**
     * A key of the game window was pressed, released, or repeated, before the game handles it; also while a screen is
     * open. Observe only: the game still handles the key. Fabric API: {@code ScreenKeyboardEvents} (screens only);
     * NeoForge: {@code InputEvent.Key}.
     */
    public static final EventKey<KeyInputListener> KEY_INPUT =
            EventKey.of("plexus:client/key_input", KeyInputListener.class);

    /**
     * A mouse button was pressed or released over the game window, before the game handles it; also while a screen
     * is open. Observe only. NeoForge: {@code InputEvent.MouseButton.Pre}.
     */
    public static final EventKey<MouseButtonListener> MOUSE_BUTTON =
            EventKey.of("plexus:client/mouse_button", MouseButtonListener.class);

    /**
     * The mouse wheel or touchpad scrolled over the game window, before the game handles it. Observe only. NeoForge:
     * {@code InputEvent.MouseScrollingEvent}.
     */
    public static final EventKey<MouseScrollListener> MOUSE_SCROLL =
            EventKey.of("plexus:client/mouse_scroll", MouseScrollListener.class);

    private ClientEvents() {}
}
