package net.fabricmc.loader.impl.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.gui.FabricStatusTree.FabricStatusTab;
import net.fabricmc.loader.impl.gui.FabricStatusTree.FabricTreeWarningLevel;
import org.junit.jupiter.api.Test;

/**
 * The stand-in for Fabric Loader's error window: mods such as ModernFix fill the {@link FabricStatusTree} Fabric Loader
 * would show (the shape their bytecode expects) and hand it over; here it is only written to the log.
 */
class FabricGuiEntryTest {

    @Test
    void theCustomiserGetsTheTreeFabricLoaderWouldShow() {
        AtomicReference<FabricStatusTree> seen = new AtomicReference<>();

        FabricGuiEntry.displayError(
                "Compatibility warning",
                new IllegalStateException("two mods disagree"),
                tree -> {
                    seen.set(tree);
                    FabricStatusTab tab = tree.addTab("Warning");
                    tab.node.addMessage("Remove one of them.", FabricTreeWarningLevel.ERROR);
                },
                false);

        FabricStatusTree tree = seen.get();
        assertNotNull(tree);
        assertEquals("Compatibility warning", tree.mainText);
        assertEquals(
                List.of("Crash", "Warning"),
                tree.tabs.stream().map(tab -> tab.node.name).toList());
        assertTrue(tree.tabs.get(0).node.children.size() >= 1, "the exception is under the Crash tab");
    }

    @Test
    void aModCanRemoveATabAsModernFixDoes() {
        AtomicReference<FabricStatusTree> seen = new AtomicReference<>();

        FabricGuiEntry.displayError(
                "Compatibility warning",
                new IllegalStateException("x"),
                tree -> {
                    FabricStatusTab warning = tree.addTab("Warning");
                    tree.tabs.removeIf(tab -> tab != warning);
                    seen.set(tree);
                },
                false);

        assertEquals(1, seen.get().tabs.size());
        assertEquals("Warning", seen.get().tabs.get(0).node.name);
    }

    @Test
    void withoutAnExceptionTheTreeSaysSo() {
        AtomicReference<FabricStatusTree> seen = new AtomicReference<>();

        FabricGuiEntry.displayError("No cause", null, seen::set, false);

        assertEquals(1, seen.get().tabs.get(0).node.children.size());
    }

    @Test
    void aFormattedExceptionCanBeShown() {
        assertDoesNotThrow(() -> FabricGuiEntry.displayError(
                "Formatted", new FormattedException("Main text", "Details"), tree -> {}, false));
    }
}
