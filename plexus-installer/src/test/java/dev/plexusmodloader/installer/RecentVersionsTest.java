package dev.plexusmodloader.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecentVersionsTest {

    private static final List<String> OFFERED =
            List.of("1.20.1", "1.20.4", "1.21.1", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3");

    @Test
    void aNewInstallGoesFirstWithoutRepeatsAndAtMostTheLimit() {
        assertEquals(List.of("1.21.1", "26.3"), RecentVersions.with(List.of("26.3", "1.21.1"), "1.21.1"));
        assertEquals(List.of("d", "a", "b"), RecentVersions.with(List.of("a", "b", "c"), "d"));
    }

    @Test
    void storedVersionsAreReadInOrderWithoutUnknownsRepeatsOrBlanks() {
        assertEquals(
                List.of("1.21.1", "26.3", "1.20.1"),
                RecentVersions.parse("1.21.1, 9.9,1.21.1,,26.3,1.20.1,26.2", OFFERED));
        assertEquals(List.of(), RecentVersions.parse("", OFFERED));
    }

    @Test
    void encodeAndParseAgree() {
        List<String> versions = List.of("26.3", "1.21.11");
        assertEquals(versions, RecentVersions.parse(RecentVersions.encode(versions), OFFERED));
    }
}
