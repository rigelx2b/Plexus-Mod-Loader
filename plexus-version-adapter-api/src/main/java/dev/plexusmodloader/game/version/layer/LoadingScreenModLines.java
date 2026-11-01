package dev.plexusmodloader.game.version.layer;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The lines under the progress bar of Plexus Mod Loader's early window and of the game's loading screen ({@code
 * PlexusLoadingOverlay}), which look the same: first the mods each loader runs, {@code Mods: Fabric 2 (+13 included)  ·
 * NeoForge 2}, the installed ones and, in brackets, those bundled inside them; then a warning line for each kind of
 * mods that are not loaded, so that a player who never reads the log sees at the first window that mods are missing.
 * The first line is empty when no mod runs, so that warnings are always from the second line on.
 *
 * <p>English only: both screens draw Plexus Mod Loader's pixel letters before the game's font and language are loaded.
 *
 * <p>Thread: any; no state.
 */
@ApiStability(StabilityLevel.INTERNAL)
public final class LoadingScreenModLines {

    private static final String LOG = "logs/latest.log";

    private LoadingScreenModLines() {}

    /**
     * Returns the lines, top to bottom: the counts line (maybe empty), then the warning lines; none when no mod runs or
     * is left out. A line naming mods names as many as fit in {@code maxLetters} letters, and how many more.
     */
    public static List<String> lines(LoadedModCounts counts, int maxLetters) {
        List<String> lines = new ArrayList<>(3);
        lines.add(countsLine(counts));
        if (counts.hostIncompatibleMods() > 0) {
            lines.add(counts.hostIncompatibleMods() + " not loaded: not yet supported by Plexus Mod Loader");
        }
        if (!counts.leftOutMods().isEmpty()) {
            lines.add(leftOutLine(counts.leftOutMods(), maxLetters));
        }
        return lines.size() == 1 && lines.getFirst().isEmpty() ? List.of() : List.copyOf(lines);
    }

    /** {@code Mods: Fabric 2 (+13 included)  ·  NeoForge 2}: the loaders whose mods run; empty with none. */
    public static String countsLine(LoadedModCounts counts) {
        LoadedModCounts.Installed installed = counts.installed();
        List<String> parts = new ArrayList<>();
        add(parts, "Fabric", counts.fabricMods(), installed.fabricMods());
        add(parts, "Quilt", counts.quiltMods(), installed.quiltMods());
        add(parts, "Forge", counts.forgeMods(), installed.forgeMods());
        add(parts, "NeoForge", counts.neoForgeMods(), installed.neoForgeMods());
        add(parts, "Plexus", counts.plexusMods(), installed.plexusMods());
        return parts.isEmpty() ? "" : "Mods: " + String.join("  ·  ", parts);
    }

    /** {@code 2 not loaded: a, b - see logs/latest.log}, with as many names as fit and how many more. */
    static String leftOutLine(List<String> mods, int maxLetters) {
        String head = mods.size() + " not loaded: ";
        String tail = " - see " + LOG;
        for (int named = mods.size(); named > 0; named--) {
            String names = String.join(", ", mods.subList(0, named));
            String more = named < mods.size() ? String.format(Locale.ROOT, " and %d more", mods.size() - named) : "";
            String line = head + names + more + tail;
            if (line.length() <= maxLetters) {
                return line;
            }
        }
        return mods.size() + " not loaded - see " + LOG;
    }

    private static void add(List<String> parts, String loader, int count, int installed) {
        if (count <= 0) {
            return;
        }
        int shown = installed > 0 ? installed : count;
        parts.add(shown < count ? loader + " " + shown + " (+" + (count - shown) + " included)" : loader + " " + count);
    }
}
