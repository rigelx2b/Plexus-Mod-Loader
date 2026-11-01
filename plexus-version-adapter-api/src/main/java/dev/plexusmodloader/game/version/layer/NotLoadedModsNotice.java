package dev.plexusmodloader.game.version.layer;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * The notice a player gets once the game has loaded, when mods are not loaded ({@link LoadedModCounts}): a title, a
 * paragraph naming the mods Plexus Mod Loader cannot run on the host's patched game ({@code PLEXUS-COMPAT-224}), one
 * naming the mods in the mods folder left out for another reason, and where each mod's reason is. The game layers show
 * it on the title screen, over it, until the player confirms, so that it is not gone before they read it, as the
 * loading screens' lines are.
 *
 * <p>Each text is a translation key of Plexus Mod Loader's language files ({@link ModCountLines#bundledLanguage}),
 * looked up as {@link ModCountLines} does; a key without text, or text that does not format, falls back to English.
 *
 * <p>Thread: any; no state.
 *
 * @param title the notice's title
 * @param paragraphs its paragraphs, top to bottom
 */
@ApiStability(StabilityLevel.INTERNAL)
public record NotLoadedModsNotice(String title, List<String> paragraphs) {

    /** Key of the title, in English {@link #TITLE_ENGLISH}. */
    public static final String TITLE_KEY = "plexus.notice.not_loaded.title";
    /** Key of the paragraph of mods left out for the host's patch; its one argument is their IDs. */
    public static final String HOST_INCOMPATIBLE_KEY = "plexus.notice.not_loaded.host_incompatible";
    /** Key of the paragraph of mods left out for another reason; its one argument is their IDs or file names. */
    public static final String LEFT_OUT_KEY = "plexus.notice.not_loaded.left_out";
    /** Key of the last paragraph, which says where each mod's reason is. */
    public static final String DETAILS_KEY = "plexus.notice.not_loaded.details";

    static final String TITLE_ENGLISH = "Some mods were not loaded";
    static final String HOST_INCOMPATIBLE_ENGLISH = "Plexus Mod Loader can't run these yet: %s";
    static final String LEFT_OUT_ENGLISH = "Missing a required mod, blocked or unreadable: %s";
    static final String DETAILS_ENGLISH = "The reason for each mod is in logs/latest.log.";

    public NotLoadedModsNotice {
        paragraphs = List.copyOf(paragraphs);
    }

    /**
     * The notice for {@code counts}; empty when every mod is loaded.
     *
     * @param translations the text of a key in the language to show, or {@code null} when that language has none
     */
    public static Optional<NotLoadedModsNotice> of(LoadedModCounts counts, Function<String, String> translations) {
        List<String> paragraphs = new ArrayList<>(3);
        if (counts.hostIncompatibleMods() > 0) {
            String mods = counts.hostIncompatibleModIds().isEmpty()
                    ? String.valueOf(counts.hostIncompatibleMods())
                    : String.join(", ", counts.hostIncompatibleModIds());
            paragraphs.add(format(translations.apply(HOST_INCOMPATIBLE_KEY), HOST_INCOMPATIBLE_ENGLISH, mods));
        }
        if (!counts.leftOutMods().isEmpty()) {
            paragraphs.add(format(
                    translations.apply(LEFT_OUT_KEY), LEFT_OUT_ENGLISH, String.join(", ", counts.leftOutMods())));
        }
        if (paragraphs.isEmpty()) {
            return Optional.empty();
        }
        paragraphs.add(text(translations.apply(DETAILS_KEY), DETAILS_ENGLISH));
        return Optional.of(new NotLoadedModsNotice(text(translations.apply(TITLE_KEY), TITLE_ENGLISH), paragraphs));
    }

    private static String text(String text, String english) {
        return text != null ? text : english;
    }

    private static String format(String text, String english, String argument) {
        if (text != null) {
            try {
                return String.format(Locale.ROOT, text, argument);
            } catch (IllegalFormatException e) {
                // A resource pack's text that does not take one argument; the English text is shown instead.
            }
        }
        return String.format(Locale.ROOT, english, argument);
    }
}
