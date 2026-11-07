package dev.plexusmodloader.game.layer.v1_20_4;

import dev.plexusmodloader.api.event.chat.ChatDraft;
import dev.plexusmodloader.api.event.chat.ChatEvents;
import dev.plexusmodloader.game.version.layer.GameLayerContext;
import java.util.function.Consumer;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/**
 * Hook for {@link ChatEvents#MESSAGE}. The transformation rule is in {@code Minecraft1_20_4Adapter}; the target
 * {@code PlayerList.broadcastChatMessage(PlayerChatMessage, ServerPlayer, ChatType$Bound)} is public and has the same
 * descriptor in every supported version (verified with {@code javap}).
 */
public final class ChatHooks {

    /** Set while the hook re-sends a rewritten message through the game's own method. */
    private static final ThreadLocal<Boolean> RESENDING = new ThreadLocal<>();

    /** Set by game tests: receives the text of every message the game goes on to deliver. */
    private static final ThreadLocal<Consumer<String>> DELIVERED = new ThreadLocal<>();

    private ChatHooks() {}

    /**
     * Head of {@code PlayerList.broadcastChatMessage(PlayerChatMessage, ServerPlayer, ChatType.Bound)}: {@code true}
     * when the game must not deliver the original message (a listener cancelled it, or the hook delivered a rewritten
     * copy itself).
     */
    public static boolean onBroadcastChat(
            PlayerList players, PlayerChatMessage message, ServerPlayer sender, ChatType.Bound bound) {
        if (RESENDING.get() != null) {
            return passOn(message);
        }
        GameLayerContext context = LayerState.context().orElse(null);
        if (context == null || !context.events().hasListeners(ChatEvents.MESSAGE) || message.isSystem()) {
            return passOn(message);
        }
        ChatDraft draft = new ChatDraft(message.signedContent());
        context.events().publish(ChatEvents.MESSAGE, listener -> {
            if (!draft.isCancelled()) {
                listener.onMessage(Views.player(sender), draft);
            }
        });
        if (draft.isCancelled()) {
            return true;
        }
        if (!draft.isModified()) {
            return passOn(message);
        }
        RESENDING.set(Boolean.TRUE);
        try {
            players.broadcastChatMessage(message.withUnsignedContent(Component.literal(draft.text())), sender, bound);
        } finally {
            RESENDING.remove();
        }
        return true;
    }

    /** Lets the game deliver {@code message}; tells a game test what the game receives. */
    private static boolean passOn(PlayerChatMessage message) {
        Consumer<String> delivered = DELIVERED.get();
        if (delivered != null) {
            delivered.accept(message.decoratedContent().getString());
        }
        return false;
    }

    /**
     * Runs {@code sending} and passes the text of each message the game goes on to deliver to {@code delivered}; a
     * message a listener cancelled is not passed. For game tests.
     */
    public static void capture(Consumer<String> delivered, Runnable sending) {
        DELIVERED.set(delivered);
        try {
            sending.run();
        } finally {
            DELIVERED.remove();
        }
    }

    /** Set while the hook re-sends a rewritten system message through the game's own method. */
    private static final ThreadLocal<Boolean> RESENDING_SYSTEM = new ThreadLocal<>();

    /** Set by game tests: receives the text of every system message instead of the player's connection. */
    private static final ThreadLocal<Consumer<String>> SYSTEM_CAPTURE = new ThreadLocal<>();

    /**
     * Head of {@code ServerPlayer.sendSystemMessage(Component, boolean)}, the method every system message to a player
     * passes (command feedback, announcements): {@code true} when the game must not send the original (a listener
     * cancelled it, or the hook sent a rewritten plain-text copy itself).
     */
    public static boolean onSendSystemMessage(ServerPlayer player, Component message, boolean overlay) {
        Consumer<String> capture = SYSTEM_CAPTURE.get();
        GameLayerContext context = LayerState.context().orElse(null);
        if (RESENDING_SYSTEM.get() != null
                || context == null
                || !context.events().hasListeners(ChatEvents.SYSTEM_MESSAGE)) {
            return captured(capture, message);
        }
        ChatDraft draft = new ChatDraft(message.getString());
        context.events().publish(ChatEvents.SYSTEM_MESSAGE, listener -> {
            if (!draft.isCancelled()) {
                listener.onMessage(Views.player(player), draft, overlay);
            }
        });
        if (draft.isCancelled()) {
            return true;
        }
        if (!draft.isModified()) {
            return captured(capture, message);
        }
        RESENDING_SYSTEM.set(Boolean.TRUE);
        try {
            player.sendSystemMessage(Component.literal(draft.text()), overlay);
        } finally {
            RESENDING_SYSTEM.remove();
        }
        return true;
    }

    /** In a game test, records the message and keeps it from the connection; otherwise lets the game send it. */
    private static boolean captured(Consumer<String> capture, Component message) {
        if (capture == null) {
            return false;
        }
        capture.accept(message.getString());
        return true;
    }

    /** Runs {@code sending}; passes the text of each system message that would be delivered to {@code delivered}. */
    public static void captureSystem(Consumer<String> delivered, Runnable sending) {
        SYSTEM_CAPTURE.set(delivered);
        try {
            sending.run();
        } finally {
            SYSTEM_CAPTURE.remove();
        }
    }
}
