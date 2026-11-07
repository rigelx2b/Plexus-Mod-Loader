package dev.plexusmodloader.installer;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * The Minecraft versions the player installed lately, newest first, kept in the player's user preferences so the
 * installer window can offer them as shortcuts. The list is a convenience only: a version that cannot be saved is not
 * remembered, and an install never fails because of it.
 *
 * <p>Only {@link #parse}, {@link #with} and {@link #encode} hold the rules and are tested without preferences; the
 * window calls {@link #load} and {@link #remember}.
 */
final class RecentVersions {

    /** How many versions the window offers as shortcuts. */
    static final int LIMIT = 3;

    private static final String KEY = "recentMinecraftVersions";

    private RecentVersions() {}

    /** The remembered versions the installer still offers, newest first, at most {@link #LIMIT}. */
    static List<String> load(Preferences preferences, List<String> offered) {
        return parse(preferences.get(KEY, ""), offered);
    }

    /**
     * Remembers {@code version} as the latest install. A failure to save is dropped on purpose: the list is only a
     * shortcut, and the install that was just finished must not look failed because of it.
     */
    static void remember(Preferences preferences, List<String> offered, String version) {
        preferences.put(KEY, encode(with(load(preferences, offered), version)));
        try {
            preferences.flush();
        } catch (BackingStoreException e) {
            // Not saved: the next install offers the versions remembered before this one.
        }
    }

    /**
     * The versions in {@code stored} (comma-separated) that {@code offered} contains, without repeats, at most {@link
     * #LIMIT}.
     */
    static List<String> parse(String stored, List<String> offered) {
        List<String> versions = new ArrayList<>();
        for (String part : stored.split(",")) {
            String version = part.strip();
            if (!version.isEmpty() && offered.contains(version) && !versions.contains(version)) {
                versions.add(version);
            }
        }
        return List.copyOf(versions.subList(0, Math.min(LIMIT, versions.size())));
    }

    /** {@code recent} with {@code version} first and without another copy of it, at most {@link #LIMIT} long. */
    static List<String> with(List<String> recent, String version) {
        List<String> updated = new ArrayList<>();
        updated.add(version);
        for (String other : recent) {
            if (!other.equals(version) && updated.size() < LIMIT) {
                updated.add(other);
            }
        }
        return List.copyOf(updated);
    }

    /** The comma-separated form {@link #parse} reads. */
    static String encode(List<String> versions) {
        return String.join(",", versions);
    }
}
