package dev.plexusmodloader.game.version.layer;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The lines the title screen shows above the game's version text: how many mods each loader loaded ({@link
 * LoadedModCounts}). One line per loader that loaded at least one mod, top to bottom Plexus, Fabric, Quilt, Forge,
 * NeoForge, each with the mods the player installed and, when some came bundled inside those, how many
 * ({@link #BUNDLED_KEY}); then, when Plexus Mod Loader left mods out because it cannot run them on the host loader's
 * patched game ({@code PLEXUS-COMPAT-224}, ADR-0030), a line that says so, so that a player who never reads the log
 * sees that those mods are missing and that Plexus Mod Loader, not the mods, has to change; then, when mods in the mods
 * folder are not loaded for another reason ({@link LoadedModCounts#leftOutMods}), a line with how many. Nothing when no
 * mod is loaded or left out.
 *
 * <p>Each line is a translation key of Plexus Mod Loader's own language files ({@code assets/plexus/lang/<language>.json}
 * in this module, {@link #bundledLanguage}) whose text takes the count as its one argument ({@code %s}), as Minecraft's
 * translations do. The game layer looks a key up in the game's current language first, so a resource pack can change
 * the text, and in Plexus Mod Loader's file for that language otherwise. A key without text, or text that does not
 * format, falls back to the English line.
 *
 * <p>Thread: any; the methods keep no state.
 */
@ApiStability(StabilityLevel.INTERNAL)
public final class ModCountLines {

    /** Key of the Plexus mods line, {@code "Plexus mods: %s"} in English. */
    public static final String PLEXUS_KEY = "plexus.title.mod_count.plexus";
    /** Key of the Fabric mods line, {@code "Fabric mods: %s"} in English. */
    public static final String FABRIC_KEY = "plexus.title.mod_count.fabric";
    /** Key of the Quilt mods line, {@code "Quilt mods: %s"} in English. */
    public static final String QUILT_KEY = "plexus.title.mod_count.quilt";
    /** Key of the Forge mods line, {@code "Forge mods: %s"} in English. */
    public static final String FORGE_KEY = "plexus.title.mod_count.forge";
    /** Key of the NeoForge mods line, {@code "NeoForge mods: %s"} in English. */
    public static final String NEOFORGE_KEY = "plexus.title.mod_count.neoforge";
    /** Key of the left-out mods line, {@code "Not yet supported by Plexus Mod Loader: %s"} in English. */
    public static final String HOST_INCOMPATIBLE_KEY = "plexus.title.mod_count.host_incompatible";
    /** Key of what follows a loader's line when some of its mods came bundled, {@code " (+%s included)"} in English. */
    public static final String BUNDLED_KEY = "plexus.title.mod_count.bundled";
    /** Key of the line of mods in the mods folder left out for another reason, in English {@link #LEFT_OUT_ENGLISH}. */
    public static final String LEFT_OUT_KEY = "plexus.title.mod_count.left_out";

    static final String BUNDLED_ENGLISH = " (+%s included)";
    static final String LEFT_OUT_ENGLISH = "Not loaded: %s (see logs/latest.log)";

    /** The language whose file every other language falls back to, as Minecraft's own. */
    public static final String DEFAULT_LANGUAGE = "en_us";

    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z0-9_-]+");

    private ModCountLines() {}

    /**
     * Returns the lines for {@code counts}, top to bottom.
     *
     * @param translations the text of a key in the language to show, or {@code null} when that language has none
     */
    public static List<String> lines(LoadedModCounts counts, Function<String, String> translations) {
        List<String> lines = new ArrayList<>(7);
        LoadedModCounts.Installed installed = counts.installed();
        String bundled = translations.apply(BUNDLED_KEY);
        add(lines, PLEXUS_KEY, "Plexus mods: %s", counts.plexusMods(), installed.plexusMods(), bundled, translations);
        add(lines, FABRIC_KEY, "Fabric mods: %s", counts.fabricMods(), installed.fabricMods(), bundled, translations);
        add(lines, QUILT_KEY, "Quilt mods: %s", counts.quiltMods(), installed.quiltMods(), bundled, translations);
        add(lines, FORGE_KEY, "Forge mods: %s", counts.forgeMods(), installed.forgeMods(), bundled, translations);
        add(
                lines,
                NEOFORGE_KEY,
                "NeoForge mods: %s",
                counts.neoForgeMods(),
                installed.neoForgeMods(),
                bundled,
                translations);
        add(
                lines,
                HOST_INCOMPATIBLE_KEY,
                "Not yet supported by Plexus Mod Loader: %s",
                counts.hostIncompatibleMods(),
                translations);
        add(lines, LEFT_OUT_KEY, LEFT_OUT_ENGLISH, counts.leftOutMods().size(), translations);
        return List.copyOf(lines);
    }

    /**
     * Opens Plexus Mod Loader's language file for {@code languageCode} (such as {@code en_us} or {@code ko_kr}), a JSON
     * object from translation key to text in Minecraft's language file format. The caller closes the stream.
     *
     * @return empty when Plexus Mod Loader has no file for that language, or the code is not a language code
     */
    public static Optional<InputStream> bundledLanguage(String languageCode) {
        if (!LANGUAGE_CODE.matcher(languageCode).matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                ModCountLines.class.getResourceAsStream("/assets/plexus/lang/" + languageCode + ".json"));
    }

    /** A loader's line: its installed mods, and after them how many came bundled ({@code bundledText}), if any. */
    private static void add(
            List<String> lines,
            String key,
            String english,
            int count,
            int installed,
            String bundledText,
            Function<String, String> translations) {
        if (count <= 0) {
            return;
        }
        // A loader whose every mod came bundled (none of its jars in the mods folder) still shows them.
        int shown = installed > 0 ? installed : count;
        String line = format(translations.apply(key), english, shown);
        if (shown < count) {
            line += format(bundledText, BUNDLED_ENGLISH, count - shown);
        }
        lines.add(line);
    }

    private static void add(
            List<String> lines, String key, String english, int count, Function<String, String> translations) {
        if (count > 0) {
            lines.add(format(translations.apply(key), english, count));
        }
    }

    /** {@code text} with {@code count}, or {@code english} with it when there is no text or it does not format. */
    private static String format(String text, String english, int count) {
        if (text != null) {
            try {
                return String.format(Locale.ROOT, text, count);
            } catch (IllegalFormatException e) {
                // A resource pack's text that does not take one count; the English text is shown instead.
            }
        }
        return String.format(Locale.ROOT, english, count);
    }
}
