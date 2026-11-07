package dev.plexusmodloader.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.installer.VersionFilter.Row;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VersionFilterTest {

    /** The nine versions of the installer, oldest first as the payload lists them. */
    private static final List<String> OFFERED =
            List.of("1.20.1", "1.20.4", "1.21.1", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3");

    private final VersionFilter filter = new VersionFilter(OFFERED, "26.3");

    @Test
    void sortsNewestFirstAndComparesNumbersByValue() {
        assertEquals(
                List.of("26.3", "26.2", "26.1.2", "26.1.1", "26.1", "1.21.11", "1.21.1", "1.20.4", "1.20.1"),
                filter.versions());
    }

    @Test
    void groupsVersionsBySeriesNewestSeriesFirst() {
        List<Row> rows = filter.rows("");
        assertEquals(
                List.of(
                        new Row.Header("26.x", 5),
                        new Row.Version("26.3", true),
                        new Row.Version("26.2", false),
                        new Row.Version("26.1.2", false),
                        new Row.Version("26.1.1", false),
                        new Row.Version("26.1", false),
                        new Row.Header("1.21.x", 2),
                        new Row.Version("1.21.11", false),
                        new Row.Version("1.21.1", false),
                        new Row.Header("1.20.x", 2),
                        new Row.Version("1.20.4", false),
                        new Row.Version("1.20.1", false)),
                rows);
    }

    @Test
    void highlightsOnlyTheDefaultVersion() {
        long highlighted = filter.rows("").stream()
                .filter(row -> row instanceof Row.Version version && version.highlighted())
                .count();
        assertEquals(1, highlighted);
        assertEquals("26.3", filter.defaultVersion());
    }

    @Test
    void blankAndNullQueriesShowEverything() {
        assertEquals(filter.rows(""), filter.rows("   "));
        assertEquals(filter.rows(""), filter.rows(null));
    }

    @Test
    void aPartOfAVersionNarrowsTheListAndHidesEmptySeries() {
        assertEquals(
                List.of(
                        new Row.Header("1.21.x", 2),
                        new Row.Version("1.21.11", false),
                        new Row.Version("1.21.1", false)),
                filter.rows("1.21"));
        assertEquals(List.of(new Row.Header("1.21.x", 1), new Row.Version("1.21.11", false)), filter.rows("1.21.11"));
    }

    @Test
    void aSeriesWithTrailingXListsThatSeries() {
        assertEquals(6, filter.rows("26.x").size());
        assertEquals(3, filter.rows("1.20.x").size());
        // "1.x" is every version of Minecraft's 1. line, and not 26.x
        assertEquals(6, filter.rows("1.x").size());
        assertTrue(filter.rows("1.x").stream()
                .noneMatch(row -> row instanceof Row.Version v && v.id().startsWith("26")));
    }

    @Test
    void caseSpacesAndLeadingVDoNotMatter() {
        assertEquals(filter.rows("26.2"), filter.rows("  V26.2 "));
        assertEquals(filter.rows("26.X"), filter.rows("26.x"));
    }

    @Test
    void nothingMatchesGivesNoRows() {
        assertTrue(filter.rows("9.9").isEmpty());
        assertTrue(filter.rows("x").isEmpty());
        assertEquals(Optional.empty(), filter.firstMatch("9.9"));
    }

    @Test
    void firstMatchPrefersTheDefaultThenTheNewest() {
        assertEquals(Optional.of("26.3"), filter.firstMatch(""));
        assertEquals(Optional.of("26.3"), filter.firstMatch("26"));
        assertEquals(Optional.of("1.21.11"), filter.firstMatch("1.21"));
        assertEquals(Optional.of("26.1.2"), filter.firstMatch("26.1.2"));
        assertEquals(Optional.of("26.1.2"), filter.firstMatch("26.1.2 "));
        assertEquals(Optional.of("26.1"), filter.firstMatch("26.1"));
    }

    @Test
    void aFullVersionSelectsItselfNotTheLongerVersionThatContainsIt() {
        assertEquals(Optional.of("1.21.1"), filter.firstMatch("1.21.1"));
        assertEquals(Optional.of("1.21.1"), filter.firstMatch(" V1.21.1 "));
        assertEquals(Optional.of("1.21.11"), filter.firstMatch("1.21.11"));
        assertEquals(Optional.empty(), filter.exactMatch("1.21"));
        assertEquals(Optional.empty(), filter.exactMatch(""));
    }

    @Test
    void everyOfferedVersionOfTheNineIsFoundByItsFullText() {
        for (String version : OFFERED) {
            assertEquals(Optional.of(version), filter.exactMatch(version), version);
            assertEquals(Optional.of(version), filter.firstMatch(version), version);
            assertTrue(filter.matchingVersions(version).contains(version), version);
            assertTrue(filter.rows(version).contains(new Row.Version(version, version.equals("26.3"))), version);
        }
    }

    @Test
    void theNineVersionsFormThreeSeriesNewestFirst() {
        assertEquals(9, filter.versions().size());
        assertEquals(
                List.of("26.x", "1.21.x", "1.20.x"),
                filter.rows("").stream()
                        .filter(Row.Header.class::isInstance)
                        .map(row -> ((Row.Header) row).series())
                        .toList());
        assertEquals(Optional.of("26.3"), filter.firstMatch(""));
        assertEquals(Optional.of("26.3"), filter.firstMatch("26.x"));
        assertEquals(Optional.of("1.20.4"), filter.firstMatch("1.20.x"));
        assertEquals(Optional.of("1.21.11"), filter.firstMatch("1.21.x"));
    }

    @Test
    void seriesNamesTheUnchangingPart() {
        assertEquals("26.x", VersionFilter.series("26.1.2"));
        assertEquals("26.x", VersionFilter.series("26.3"));
        assertEquals("1.21.x", VersionFilter.series("1.21.11"));
        assertEquals("1.20.x", VersionFilter.series("1.20"));
        assertEquals("1.x", VersionFilter.series("1"));
        assertEquals("snapshot", VersionFilter.series("snapshot"));
    }

    @Test
    void aPreReleaseComesBeforeItsRelease() {
        VersionFilter withPreRelease = new VersionFilter(List.of("26.4", "26.4-rc1", "26.3"), "26.4");
        assertEquals(List.of("26.4", "26.4-rc1", "26.3"), withPreRelease.versions());
    }

    @Test
    void worksForAListWithoutTheDefaultAndForOneVersion() {
        VersionFilter single = new VersionFilter(List.of("26.3"), "26.3");
        assertEquals(List.of(new Row.Header("26.x", 1), new Row.Version("26.3", true)), single.rows(""));
        VersionFilter noDefault = new VersionFilter(List.of("1.20.1", "26.1"), "0.0");
        assertFalse(noDefault.rows("").stream().anyMatch(row -> row instanceof Row.Version v && v.highlighted()));
        assertEquals(Optional.of("26.1"), noDefault.firstMatch(""));
    }

    @Test
    void duplicatesAreListedOnce() {
        assertEquals(List.of("26.3", "26.2"), new VersionFilter(List.of("26.2", "26.3", "26.2"), "26.3").versions());
    }

    @Test
    void matchingVersionsGivesTheFullVersionsAPartialTextStandsFor() {
        assertEquals(List.of("1.21.11", "1.21.1"), filter.matchingVersions("1.21"));
        assertEquals(List.of("26.3", "26.2", "26.1.2", "26.1.1", "26.1"), filter.matchingVersions(" V26 "));
        assertEquals(List.of(), filter.matchingVersions("9.9"));
        assertEquals(filter.versions(), filter.matchingVersions(""));
    }
}
