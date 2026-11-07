package dev.plexusmodloader.api.event.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatDraftTest {

    @Test
    void startsUnmodifiedWithTheTypedText() {
        ChatDraft draft = new ChatDraft("hello");
        assertEquals("hello", draft.text());
        assertEquals("hello", draft.original());
        assertFalse(draft.isModified());
        assertFalse(draft.isCancelled());
    }

    @Test
    void replacedTextIsSeenByTheNextListenerAndKeepsTheOriginal() {
        ChatDraft draft = new ChatDraft("hello");
        draft.replaceText("HELLO");
        assertEquals("HELLO", draft.text());
        assertEquals("hello", draft.original());
        assertTrue(draft.isModified());
    }

    @Test
    void replacingWithTheSameTextIsNotAModification() {
        ChatDraft draft = new ChatDraft("hello");
        draft.replaceText("hello");
        assertFalse(draft.isModified());
    }

    @Test
    void cancelDropsTheMessage() {
        ChatDraft draft = new ChatDraft("hello");
        draft.cancel();
        assertTrue(draft.isCancelled());
    }

    @Test
    void nullTextIsRejected() {
        assertThrows(NullPointerException.class, () -> new ChatDraft(null));
        assertThrows(NullPointerException.class, () -> new ChatDraft("a").replaceText(null));
    }
}
