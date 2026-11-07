package dev.plexusmodloader.api.event.chat;

import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/** Listener for {@link ChatEvents#MESSAGE}. */
@ApiStability(StabilityLevel.PREVIEW)
@FunctionalInterface
public interface ChatMessageListener {

    /**
     * Called on the server thread when a player's chat message is about to be delivered, before any player receives
     * it. Commands are not chat messages. The player view is valid only during the call.
     *
     * <p>A listener that throws is disabled and logged ({@code PLEXUS-EVENT-} code); the message goes on with the draft
     * as it stands.
     *
     * @param sender the player who sent the message
     * @param draft the message; call {@link ChatDraft#replaceText} to change it or {@link ChatDraft#cancel} to drop it
     */
    void onMessage(PlayerView sender, ChatDraft draft);
}
