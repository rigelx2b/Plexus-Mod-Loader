package dev.plexusmodloader.api.event.chat;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.Objects;

/**
 * A chat message a player sent that the server has not delivered yet. Listeners of {@link ChatEvents#MESSAGE} read the
 * text, replace it, or cancel the message.
 *
 * <p>The game layer creates one draft per message and hands it to every listener in registration order, so a later
 * listener sees the text an earlier one set. The draft is valid only during the event.
 */
@ApiStability(StabilityLevel.PREVIEW)
public final class ChatDraft {

    private final String original;
    private String text;
    private boolean cancelled;

    /**
     * Creates a draft. Called by the game layer when a player's message reaches the server; mods have no reason to
     * call it.
     *
     * @param text what the player typed
     */
    public ChatDraft(String text) {
        this.original = Objects.requireNonNull(text, "text");
        this.text = text;
    }

    /** The text as it stands now: what the player typed, or what an earlier listener replaced it with. */
    public String text() {
        return text;
    }

    /** What the player typed, before any listener changed it. */
    public String original() {
        return original;
    }

    /**
     * Replaces the text everyone receives. Clients show the new text under the sender's name; the message stays
     * attributed to the sender. Has no effect once the message is {@linkplain #cancel() cancelled}.
     *
     * @throws NullPointerException when {@code newText} is {@code null}
     */
    public void replaceText(String newText) {
        this.text = Objects.requireNonNull(newText, "newText");
    }

    /**
     * Drops the message: nobody receives it, including the sender, and listeners after this one are not called. The
     * game does not tell the sender; send them a message yourself if they should know.
     */
    public void cancel() {
        this.cancelled = true;
    }

    /** {@code true} when a listener cancelled the message. */
    public boolean isCancelled() {
        return cancelled;
    }

    /** {@code true} when a listener changed the text. */
    public boolean isModified() {
        return !text.equals(original);
    }
}
