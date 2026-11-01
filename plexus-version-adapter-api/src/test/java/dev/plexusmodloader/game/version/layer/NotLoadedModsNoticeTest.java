package dev.plexusmodloader.game.version.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotLoadedModsNoticeTest {

    private static final LoadedModCounts COUNTS = new LoadedModCounts(0, 2, 0, 0, 1)
            .withHostIncompatibleMods(List.of("fancymenu"))
            .withLeftOutMods(List.of("sophisticatedbackpacks"));

    @Test
    void noNoticeWhenEveryModIsLoaded() {
        assertTrue(NotLoadedModsNotice.of(new LoadedModCounts(0, 2, 0), key -> null)
                .isEmpty());
    }

    @Test
    void namesTheModsOfEachKindAndWhereTheReasonsAre() throws IOException {
        assertEquals(
                new NotLoadedModsNotice(
                        "Some mods were not loaded",
                        List.of(
                                "Plexus Mod Loader can't run these yet: fancymenu",
                                "Missing a required mod, blocked or unreadable: sophisticatedbackpacks",
                                "The reason for each mod is in logs/latest.log.")),
                NotLoadedModsNotice.of(COUNTS, bundled("en_us")::get).orElseThrow());
        NotLoadedModsNotice korean =
                NotLoadedModsNotice.of(COUNTS, bundled("ko_kr")::get).orElseThrow();
        assertEquals(bundled("ko_kr").get(NotLoadedModsNotice.TITLE_KEY), korean.title());
        assertTrue(
                korean.paragraphs().get(1).endsWith(": sophisticatedbackpacks"),
                korean.paragraphs().toString());
    }

    @Test
    void withoutTextsTheEnglishOnesAndOnlyTheKindsThereAre() {
        NotLoadedModsNotice notice = NotLoadedModsNotice.of(
                        LoadedModCounts.NONE.withLeftOutMods(List.of("broken.jar")), key -> null)
                .orElseThrow();
        assertEquals(
                List.of(
                        "Missing a required mod, blocked or unreadable: broken.jar",
                        "The reason for each mod is in logs/latest.log."),
                notice.paragraphs());
    }

    /** Counts made with only a number of mods left out for the host's patch name that number. */
    @Test
    void withoutTheirIdsTheHostsLeftOutModsAreCounted() {
        NotLoadedModsNotice notice = NotLoadedModsNotice.of(
                        LoadedModCounts.NONE.withHostIncompatibleMods(3), key -> null)
                .orElseThrow();
        assertEquals(
                "Plexus Mod Loader can't run these yet: 3", notice.paragraphs().getFirst());
    }

    @Test
    void theIdsOfTheHostsLeftOutModsMatchTheirNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoadedModCounts(
                        0,
                        0,
                        0,
                        0,
                        0,
                        2,
                        new LoadedModCounts.Installed(0, 0, 0, 0, 0),
                        List.of(),
                        List.of("only_one")));
    }

    private static Map<String, String> bundled(String language) throws IOException {
        try (InputStream in = ModCountLines.bundledLanguage(language).orElseThrow()) {
            return new Gson()
                    .fromJson(
                            new InputStreamReader(in, StandardCharsets.UTF_8),
                            new TypeToken<LinkedHashMap<String, String>>() {}.getType());
        }
    }
}
