package dev.plexusmodloader.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.installer.InstallerWindow.Language;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** The picker's behaviour without a window: Swing components can be built and driven headless. */
class VersionPickerTest {

    private static final List<String> OFFERED =
            List.of("1.20.1", "1.20.4", "1.21.1", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3");

    private interface Check {
        void run(VersionPicker picker);
    }

    private static void onPicker(Language language, Check check)
            throws InterruptedException, InvocationTargetException {
        onPicker(language, List.of(), check);
    }

    private static void onPicker(Language language, List<String> recent, Check check)
            throws InterruptedException, InvocationTargetException {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(() -> check.run(
                new VersionPicker(new VersionFilter(OFFERED, "26.3"), InstallerWindow.texts(language), recent)));
    }

    /** The button of {@code root}'s tree with {@code text}, or null. */
    private static JButton button(Component root, String text) {
        if (root instanceof JButton button && text.equals(button.getText())) {
            return button;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = button(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void startsOnTheDefaultVersion() throws Exception {
        onPicker(Language.ENGLISH, picker -> assertEquals(Optional.of("26.3"), picker.selectedVersion()));
    }

    @Test
    void typingNarrowsTheListAndSelectsTheBestMatch() throws Exception {
        onPicker(Language.KOREAN, picker -> {
            picker.setQuery("1.21");
            assertEquals(Optional.of("1.21.11"), picker.selectedVersion());
            picker.setQuery("1.20.4");
            assertEquals(Optional.of("1.20.4"), picker.selectedVersion());
        });
    }

    @Test
    void theChoiceStaysWhileItStillMatches() throws Exception {
        onPicker(Language.ENGLISH, picker -> {
            picker.select("26.1.1");
            picker.setQuery("26.1.");
            assertEquals(Optional.of("26.1.1"), picker.selectedVersion());
        });
    }

    @Test
    void typingAFullVersionSelectsThatVersionEvenIfAnotherStaysChosen() throws Exception {
        onPicker(Language.ENGLISH, picker -> {
            picker.select("1.21.11");
            picker.setQuery("1.21.1");
            assertEquals(Optional.of("1.21.1"), picker.selectedVersion());
            picker.setQuery("26.1");
            assertEquals(Optional.of("26.1"), picker.selectedVersion());
        });
    }

    @Test
    void nothingIsSelectedWhileNoVersionMatchesAndClearingBringsTheDefaultBack() throws Exception {
        onPicker(Language.ENGLISH, picker -> {
            picker.setQuery("9.9");
            assertEquals(Optional.empty(), picker.selectedVersion());
            picker.setQuery("");
            assertEquals(Optional.of("26.3"), picker.selectedVersion());
        });
    }

    @Test
    void selectingAHiddenVersionClearsTheSearch() throws Exception {
        onPicker(Language.ENGLISH, picker -> {
            picker.setQuery("26");
            picker.select("1.20.1");
            assertEquals(Optional.of("1.20.1"), picker.selectedVersion());
            assertEquals("", picker.query());
        });
    }

    @Test
    void arrowsMoveThroughVersionsAndSkipSeriesHeadings() throws Exception {
        onPicker(Language.ENGLISH, picker -> {
            picker.move(1);
            assertEquals(Optional.of("26.2"), picker.selectedVersion());
            picker.select("26.1");
            picker.move(1);
            // the heading "1.21.x" sits between 26.1 and 1.21.11
            assertEquals(Optional.of("1.21.11"), picker.selectedVersion());
            picker.move(-1);
            assertEquals(Optional.of("26.1"), picker.selectedVersion());
        });
    }

    @Test
    void arrowsStopAtTheEnds() throws Exception {
        onPicker(Language.ENGLISH, picker -> {
            picker.move(-1);
            assertEquals(Optional.of("26.3"), picker.selectedVersion());
            picker.select("1.20.1");
            picker.move(1);
            assertEquals(Optional.of("1.20.1"), picker.selectedVersion());
        });
    }

    @Test
    void arrowsStayInsideTheNarrowedList() throws Exception {
        onPicker(Language.ENGLISH, picker -> {
            picker.setQuery("1.21");
            picker.move(1);
            assertEquals(Optional.of("1.21.1"), picker.selectedVersion());
            picker.move(1);
            assertTrue(picker.selectedVersion().isPresent());
            assertEquals(Optional.of("1.21.1"), picker.selectedVersion());
        });
    }

    @Test
    void aSeriesHoldingTheSelectedVersionStaysOpen() throws Exception {
        onPicker(Language.ENGLISH, picker -> {
            picker.toggleSeries("26.x");
            assertEquals(Optional.of("26.3"), picker.selectedVersion());
            picker.select("1.21.1");
            picker.toggleSeries("26.x");
            assertEquals(Optional.of("1.21.1"), picker.selectedVersion());
        });
    }

    @Test
    void aFoldedSeriesCannotBeSteppedIntoUntilSelectingOneOpensIt() throws Exception {
        onPicker(Language.ENGLISH, picker -> {
            picker.select("1.21.1");
            picker.toggleSeries("26.x");
            // 26.x is folded, so the step up from 1.21.11 finds no version above it and stays
            picker.select("1.21.11");
            picker.move(-1);
            assertEquals(Optional.of("1.21.11"), picker.selectedVersion());
            picker.select("26.2");
            assertEquals(Optional.of("26.2"), picker.selectedVersion());
        });
    }

    @Test
    void aSearchShowsFoldedSeriesToo() throws Exception {
        onPicker(Language.ENGLISH, picker -> {
            picker.select("1.20.1");
            picker.toggleSeries("1.21.x");
            picker.setQuery("1.21");
            assertEquals(Optional.of("1.21.11"), picker.selectedVersion());
            picker.move(1);
            assertEquals(Optional.of("1.21.1"), picker.selectedVersion());
        });
    }

    @Test
    void theShortcutsSelectTheDefaultAndTheRecentVersionsAndClearTheSearch() throws Exception {
        onPicker(Language.ENGLISH, List.of("1.21.1", "26.3", "1.20.1", "1.20.4"), picker -> {
            assertEquals(Optional.of("26.3"), picker.selectedVersion());
            assertNull(button(picker.component(), "26.3"));
            picker.setQuery("26");
            button(picker.component(), "1.21.1").doClick();
            assertEquals(Optional.of("1.21.1"), picker.selectedVersion());
            assertEquals("", picker.query());
            button(picker.component(), "Default 26.3").doClick();
            assertEquals(Optional.of("26.3"), picker.selectedVersion());
        });
    }
}
