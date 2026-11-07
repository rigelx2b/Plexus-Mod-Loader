package dev.plexusmodloader.api.event.chat;

import dev.plexusmodloader.api.event.EventKey;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Events about the server's chat. */
@ApiStability(StabilityLevel.PREVIEW)
public final class ChatEvents {

    /**
     * A player sent a chat message and the server is about to deliver it. Fired on the server thread for messages
     * players type in chat (not for commands, {@code /say}, or messages mods send), so listeners can filter, rewrite, or
     * drop them. Fabric API: {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE} and {@code CHAT_MESSAGE}; NeoForge:
     * {@code ServerChatEvent}.
     *
     * <p>Listeners run in registration order and see the text earlier listeners set; once one cancels the draft, the
     * rest are skipped. See {@link ChatDraft}.
     */
    public static final EventKey<ChatMessageListener> MESSAGE =
            EventKey.of("plexus:chat/message", ChatMessageListener.class);

    /**
     * A system message is about to reach one player (since 2.1.0): command feedback ({@code /give} replies), join and
     * leave messages, death messages, advancement announcements, and messages other mods send with the game's system
     * message methods. Not typed chat (see {@link #MESSAGE}). Fired on the server thread once per receiving player, so
     * a broadcast to ten players fires ten times.
     *
     * <p>The listener sees the message as plain text ({@link ChatDraft}). Cancelling drops it for that player. Changing
     * the text sends a plain-text message in its place, so colours, hover text and click events of the original are lost
     * for changed messages; unchanged messages keep their formatting. Fabric API: {@code
     * ServerMessageEvents.ALLOW_GAME_MESSAGE} and {@code GAME_MESSAGE}; NeoForge: {@code ServerChatEvent} does not
     * cover system messages.
     */
    public static final EventKey<SystemMessageListener> SYSTEM_MESSAGE =
            EventKey.of("plexus:chat/system_message", SystemMessageListener.class);

    private ChatEvents() {}
}
