package net.fabricmc.loader.impl.gui;

import java.util.function.Consumer;
import net.fabricmc.loader.impl.gui.FabricStatusTree.FabricStatusNode;
import net.fabricmc.loader.impl.gui.FabricStatusTree.FabricStatusTab;
import net.fabricmc.loader.impl.gui.FabricStatusTree.FabricTreeWarningLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plexus Mod Loader's stand-in for Fabric Loader's internal {@code FabricGuiEntry}, with Fabric Loader 0.19.5's public
 * signatures. Mods call it to show a problem to the player (ModernFix warns about an option that does not work with
 * another mod). Fabric Loader opens a Swing window in a separate JVM; Plexus Mod Loader shows no window here. It builds
 * the same {@link FabricStatusTree} (so a mod's customiser runs and reads what it expects), writes the tree to the log
 * at error level, and stops the game when the mod asked for that ({@code exitAfter}), as Fabric Loader does after the
 * window is closed.
 *
 * <p>Thread: any thread.
 */
public final class FabricGuiEntry {

    private static final Logger LOG = LoggerFactory.getLogger("FabricLoader/Gui");

    public FabricGuiEntry() {}

    /** Fabric Loader opens its window for {@code tree}; here the tree is written to the log. */
    public static void open(FabricStatusTree tree) {
        LOG.error("{}: {}", tree.title, tree.mainText);
        for (FabricStatusTab tab : tree.tabs) {
            LOG.error("[{}]", tab.node.name);
            for (FabricStatusNode child : tab.node.children) {
                write(child, 1);
            }
        }
    }

    /** A critical error: logged, then the game stops when {@code exitAfter}. */
    public static void displayCriticalError(Throwable exception, boolean exitAfter) {
        displayError("A critical error occurred", exception, tree -> {}, exitAfter);
    }

    public static void displayError(String mainText, Throwable exception, boolean exitAfter) {
        displayError(mainText, exception, tree -> {}, exitAfter);
    }

    /**
     * @param treeCustomiser changes the tree before it is shown; it gets the tree Fabric Loader would show (a "Crash"
     *     tab with the exception, an exit button)
     * @param exitAfter whether to stop the game afterwards
     */
    public static void displayError(
            String mainText, Throwable exception, Consumer<FabricStatusTree> treeCustomiser, boolean exitAfter) {
        FabricStatusTree tree = new FabricStatusTree("Fabric Loader (Plexus Mod Loader)", mainText);
        FabricStatusTab crashTab = tree.addTab("Crash");
        if (exception != null) {
            crashTab.node.addCleanedException(exception);
        } else {
            crashTab.node.addMessage("Missing exception", FabricTreeWarningLevel.ERROR);
        }
        treeCustomiser.accept(tree);
        open(tree);
        if (exitAfter) {
            LOG.error("Exiting.");
            System.exit(1);
        }
    }

    private static void write(FabricStatusNode node, int depth) {
        LOG.error("{}{}", "  ".repeat(depth), node.name);
        for (FabricStatusNode child : node.children) {
            write(child, depth + 1);
        }
    }
}
