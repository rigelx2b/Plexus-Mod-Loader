package dev.plexusmodloader.api.event.chat;

import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link ChatEvents#SYSTEM_MESSAGE}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface SystemMessageListener {

    /**
     * Called on the server thread before a system message reaches a player. The draft holds the message as plain text
     * (formatting and click events are not part of it).
     *
     * @param receiver the player who is about to get the message
     * @param draft the text; replace it or cancel it
     * @param overlay {@code true} when the message goes to the action bar instead of the chat
     */
    void onMessage(PlayerView receiver, ChatDraft draft, boolean overlay);
}
