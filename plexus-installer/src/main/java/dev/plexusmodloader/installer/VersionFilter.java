package dev.plexusmodloader.installer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders the Minecraft versions the installer offers, groups them by series (26.x, 1.21.x, 1.20.x) and narrows them
 * to what a search text matches. It knows nothing of Swing, so the window only draws {@link Row}s.
 *
 * <p>The versions come from the installer's payload; no version is named here. A series is the part of a version that
 * stays when only the last number changes: {@code 26.1.2} belongs to {@code 26.x}, {@code 1.21.11} to {@code
 * 1.21.x} (Minecraft's {@code 1.} prefix is part of the series, its second number is not).
 *
 * <p>A search text matches a version when it is a part of the version ({@code 1.21} finds {@code 1.21.1} and {@code
 * 1.21.11}), or when it names a series with a trailing {@code .x} ({@code 26.x}, {@code 1.x}). Case, spaces around the
 * text and a leading {@code v} do not matter.
 */
final class VersionFilter {

    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+)(?:\\.(\\d+))?");
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^(\\d+)(.*)$", Pattern.DOTALL);

    /** One line of the version list. */
    sealed interface Row {

        /**
         * A series heading.
         *
         * @param series the series, such as {@code 26.x}
         * @param count how many versions of the series are listed below it
         */
        record Header(String series, int count) implements Row {

            /** The series, which is what a list's type-to-select compares. */
            @Override
            public String toString() {
                return series;
            }
        }

        /**
         * A selectable version.
         *
         * @param id the version, such as {@code 26.3}
         * @param highlighted whether it is the installer's default version
         */
        record Version(String id, boolean highlighted) implements Row {

            /** The version, which is what a list's type-to-select compares. */
            @Override
            public String toString() {
                return id;
            }
        }
    }

    private final List<String> newestFirst;
    private final String defaultVersion;

    /**
     * @param versions the offered versions, in any order
     * @param defaultVersion the version the installer preselects and highlights
     */
    VersionFilter(List<String> versions, String defaultVersion) {
        this.newestFirst =
                versions.stream().distinct().sorted(VERSION_ORDER.reversed()).toList();
        this.defaultVersion = defaultVersion;
    }

    /** All offered versions, newest first. */
    List<String> versions() {
        return newestFirst;
    }

    /** The version the installer preselects. */
    String defaultVersion() {
        return defaultVersion;
    }

    /**
     * The offered versions a search text matches, newest first, without headings. The command line uses it to suggest
     * full versions for a partial one such as {@code 1.21}.
     */
    List<String> matchingVersions(String query) {
        String normalized = normalize(query);
        return newestFirst.stream()
                .filter(version -> matches(version, normalized))
                .toList();
    }

    /**
     * The rows to show for {@code query}: for every series with a match its heading and the matching versions, newest
     * series and newest version first. A blank query shows everything; a query nothing matches gives no row.
     */
    List<Row> rows(String query) {
        String normalized = normalize(query);
        Map<String, List<String>> bySeries = new LinkedHashMap<>();
        for (String version : newestFirst) {
            if (matches(version, normalized)) {
                bySeries.computeIfAbsent(series(version), key -> new ArrayList<>())
                        .add(version);
            }
        }
        List<Row> rows = new ArrayList<>();
        bySeries.forEach((series, versions) -> {
            rows.add(new Row.Header(series, versions.size()));
            for (String version : versions) {
                rows.add(new Row.Version(version, version.equals(defaultVersion)));
            }
        });
        return rows;
    }

    /**
     * The offered version the search names in full (ignoring case, spaces and a leading {@code v}), if any. A full
     * version wins over the longer versions that contain it: {@code 1.21.1} is not {@code 1.21.11}.
     */
    Optional<String> exactMatch(String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return newestFirst.stream()
                .filter(version -> version.toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst();
    }

    /**
     * The version a search would select first: the one it names in full, otherwise the default if it matches, otherwise
     * the newest match.
     */
    Optional<String> firstMatch(String query) {
        Optional<String> exact = exactMatch(query);
        if (exact.isPresent()) {
            return exact;
        }
        String normalized = normalize(query);
        if (matches(defaultVersion, normalized) && newestFirst.contains(defaultVersion)) {
            return Optional.of(defaultVersion);
        }
        return newestFirst.stream()
                .filter(version -> matches(version, normalized))
                .findFirst();
    }

    /**
     * The series of {@code version}: {@code 26.x} for {@code 26.1.2}, {@code 1.21.x} for {@code 1.21.11}. A text that
     * does not start with a number is its own series.
     */
    static String series(String version) {
        Matcher matcher = LEADING_NUMBER.matcher(version);
        if (!matcher.find()) {
            return version;
        }
        if (matcher.group(1).equals("1") && matcher.group(2) != null) {
            return "1." + matcher.group(2) + ".x";
        }
        return matcher.group(1) + ".x";
    }

    private static boolean matches(String version, String normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            return true;
        }
        String lower = version.toLowerCase(Locale.ROOT);
        if (normalizedQuery.endsWith(".x")) {
            String prefix = normalizedQuery.substring(0, normalizedQuery.length() - 2);
            return !prefix.isEmpty() && (lower.equals(prefix) || lower.startsWith(prefix + "."));
        }
        return lower.contains(normalizedQuery);
    }

    private static String normalize(String query) {
        String text = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return text.length() > 1 && text.charAt(0) == 'v' && Character.isDigit(text.charAt(1))
                ? text.substring(1)
                : text;
    }

    /** Numbers by value, so 1.21.11 follows 1.21.1 and 26.1.2 follows 26.1.1; a release follows its pre-releases. */
    private static final Comparator<String> VERSION_ORDER = (left, right) -> {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int compared = comparePart(a[i], b[i]);
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(a.length, b.length);
    };

    private static int comparePart(String left, String right) {
        Matcher a = NUMBER_PREFIX.matcher(left);
        Matcher b = NUMBER_PREFIX.matcher(right);
        if (a.matches() && b.matches()) {
            int compared = Long.compare(Long.parseLong(a.group(1)), Long.parseLong(b.group(1)));
            if (compared != 0) {
                return compared;
            }
            String suffixA = a.group(2);
            String suffixB = b.group(2);
            if (suffixA.isEmpty() != suffixB.isEmpty()) {
                return suffixA.isEmpty() ? 1 : -1;
            }
            return suffixA.compareTo(suffixB);
        }
        return left.compareTo(right);
    }
}
