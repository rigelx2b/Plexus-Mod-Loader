package dev.plexusmodloader.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.installer.InstallerWindow.Language;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class InstallerWindowTextTest {

    @Test
    void everyLanguageHasEveryString() {
        TreeSet<String> english =
                new TreeSet<>(InstallerWindow.texts(Language.ENGLISH).keySet());
        for (Language language : Language.values()) {
            assertEquals(
                    english,
                    new TreeSet<>(InstallerWindow.texts(language).keySet()),
                    language + " must have exactly the strings English has");
        }
    }

    @Test
    void versionStringsHaveTheirPlaceholdersInEveryLanguage() {
        for (Language language : Language.values()) {
            Map<String, String> texts = InstallerWindow.texts(language);
            // The picker formats these two with a series and with a search text and a count.
            assertEquals("26.x", texts.get("versionSeries").formatted("26.x").replaceAll("[^0-9.x]", ""));
            String none = texts.get("versionNone").formatted("9.9", 9);
            assertTrue(none.contains("9.9") && none.contains("9"), language + ": " + none);
            assertFalse(texts.get("versionSearch").isBlank());
            assertFalse(texts.get("versionDefault").isBlank());
            assertFalse(texts.get("versionChoose").isBlank());
        }
    }
}
