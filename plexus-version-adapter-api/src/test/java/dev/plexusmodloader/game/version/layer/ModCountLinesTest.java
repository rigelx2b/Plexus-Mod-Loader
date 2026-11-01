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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModCountLinesTest {

    private static final LoadedModCounts ALL_LOADERS = new LoadedModCounts(2, 14, 3, 5, 7);

    @Test
    void englishLinesForEveryLoaderTopToBottom() throws IOException {
        Map<String, String> english = bundled("en_us");

        assertEquals(
                List.of("Plexus mods: 2", "Fabric mods: 14", "Quilt mods: 5", "Forge mods: 3", "NeoForge mods: 7"),
                ModCountLines.lines(ALL_LOADERS, english::get));
    }

    @Test
    void koreanLines() throws IOException {
        Map<String, String> korean = bundled("ko_kr");

        assertEquals(
                List.of("Plexus 모드: 2개", "Fabric 모드: 14개", "Quilt 모드: 5개", "Forge 모드: 3개", "NeoForge 모드: 7개"),
                ModCountLines.lines(ALL_LOADERS, korean::get));
    }

    @Test
    void bothBundledLanguagesHaveEveryKey() throws IOException {
        for (String language : List.of("en_us", "ko_kr")) {
            assertEquals(
                    List.of(
                            ModCountLines.PLEXUS_KEY,
                            ModCountLines.FABRIC_KEY,
                            ModCountLines.QUILT_KEY,
                            ModCountLines.FORGE_KEY,
                            ModCountLines.NEOFORGE_KEY,
                            ModCountLines.HOST_INCOMPATIBLE_KEY,
                            ModCountLines.BUNDLED_KEY,
                            ModCountLines.LEFT_OUT_KEY,
                            NotLoadedModsNotice.TITLE_KEY,
                            NotLoadedModsNotice.HOST_INCOMPATIBLE_KEY,
                            NotLoadedModsNotice.LEFT_OUT_KEY,
                            NotLoadedModsNotice.DETAILS_KEY),
                    List.copyOf(bundled(language).keySet()),
                    language);
        }
    }

    @Test
    void loadersWithoutModsHaveNoLine() throws IOException {
        Map<String, String> english = bundled("en_us");

        assertEquals(List.of("Fabric mods: 1"), ModCountLines.lines(new LoadedModCounts(0, 1, 0), english::get));
        assertEquals(
                List.of("Plexus mods: 1", "Forge mods: 5"),
                ModCountLines.lines(new LoadedModCounts(1, 0, 5), english::get));
        assertEquals(List.of("Quilt mods: 2"), ModCountLines.lines(new LoadedModCounts(0, 0, 0, 2), english::get));
        assertEquals(
                List.of("Plexus mods: 1", "NeoForge mods: 4"),
                ModCountLines.lines(new LoadedModCounts(1, 0, 0, 0, 4), english::get));
        assertEquals(List.of(), ModCountLines.lines(LoadedModCounts.NONE, english::get));
    }

    /** Mods left out for the host's patch are shown last, so the player sees they are missing (ADR-0030). */
    @Test
    void modsLeftOutForTheHostsPatchHaveTheirOwnLine() throws IOException {
        LoadedModCounts counts = new LoadedModCounts(0, 3, 0, 0, 40).withHostIncompatibleMods(12);

        assertEquals(
                List.of("Fabric mods: 3", "NeoForge mods: 40", "Not yet supported by Plexus Mod Loader: 12"),
                ModCountLines.lines(counts, bundled("en_us")::get));
        assertEquals(
                List.of("Fabric 모드: 3개", "NeoForge 모드: 40개", "Plexus Mod Loader가 아직 지원하지 않는 모드: 12개"),
                ModCountLines.lines(counts, bundled("ko_kr")::get));
        assertEquals(
                List.of("Not yet supported by Plexus Mod Loader: 1"),
                ModCountLines.lines(LoadedModCounts.NONE.withHostIncompatibleMods(1), key -> null));
    }

    @Test
    void aKeyWithoutTextFallsBackToEnglish() {
        assertEquals(
                List.of("Plexus mods: 2", "Fabric mods: 14", "Quilt mods: 5", "Forge mods: 3", "NeoForge mods: 7"),
                ModCountLines.lines(ALL_LOADERS, key -> null));
    }

    /** A resource pack's text replaces Plexus Mod Loader's; text that cannot take the count is not shown. */
    @Test
    void textThatDoesNotFormatFallsBackToEnglish() {
        Map<String, String> pack = Map.of(
                ModCountLines.PLEXUS_KEY, "Native: %s",
                ModCountLines.FABRIC_KEY, "Fabric: %d",
                ModCountLines.FORGE_KEY, "Forge: %s and %s");

        assertEquals(
                List.of("Native: 2", "Fabric: 14", "Quilt mods: 5", "Forge mods: 3", "NeoForge mods: 7"),
                ModCountLines.lines(ALL_LOADERS, pack::get));
    }

    @Test
    void onlyLanguageCodesOpenAFile() throws IOException {
        assertTrue(ModCountLines.bundledLanguage("../lang/en_us").isEmpty());
        assertTrue(ModCountLines.bundledLanguage("EN_US").isEmpty());
        assertTrue(ModCountLines.bundledLanguage("xx_yy").isEmpty());
        try (InputStream in = ModCountLines.bundledLanguage("en_us").orElseThrow()) {
            assertTrue(in.readAllBytes().length > 0);
        }
    }

    /** Sodium and Iris installed, 13 Fabric API modules and libraries inside them: the player's 2, then the rest. */
    @Test
    void bundledModsFollowTheInstalledOnes() throws IOException {
        LoadedModCounts counts =
                new LoadedModCounts(0, 15, 0, 0, 2).withInstalled(new LoadedModCounts.Installed(0, 2, 0, 0, 2));

        assertEquals(
                List.of("Fabric mods: 2 (+13 included)", "NeoForge mods: 2"),
                ModCountLines.lines(counts, bundled("en_us")::get));
        assertEquals(
                List.of("Fabric 모드: 2개 (+13개 포함)", "NeoForge 모드: 2개"),
                ModCountLines.lines(counts, bundled("ko_kr")::get));
        assertEquals(
                List.of("Fabric mods: 2 (+13 included)", "NeoForge mods: 2"), ModCountLines.lines(counts, key -> null));
    }

    /** A loader none of whose mods is in the mods folder (all bundled in another loader's jar) still shows them. */
    @Test
    void aLoaderWithOnlyBundledModsShowsThemAll() {
        LoadedModCounts counts =
                new LoadedModCounts(0, 3, 0, 0, 1).withInstalled(new LoadedModCounts.Installed(0, 0, 0, 0, 1));

        assertEquals(List.of("Fabric mods: 3", "NeoForge mods: 1"), ModCountLines.lines(counts, key -> null));
    }

    /** Mods in the mods folder that are not loaded for another reason have a line last, with how many. */
    @Test
    void modsLeftOutForOtherReasonsHaveTheLastLine() throws IOException {
        LoadedModCounts counts = new LoadedModCounts(0, 2, 0, 0, 0)
                .withHostIncompatibleMods(1)
                .withLeftOutMods(List.of("sophisticatedbackpacks", "broken.jar"));

        assertEquals(
                List.of(
                        "Fabric mods: 2",
                        "Not yet supported by Plexus Mod Loader: 1",
                        "Not loaded: 2 (see logs/latest.log)"),
                ModCountLines.lines(counts, bundled("en_us")::get));
        assertEquals(
                "로드되지 않은 모드: 2개 (logs/latest.log 확인)",
                ModCountLines.lines(counts, bundled("ko_kr")::get).getLast());
        assertEquals(
                List.of("Not loaded: 1 (see logs/latest.log)"),
                ModCountLines.lines(LoadedModCounts.NONE.withLeftOutMods(List.of("a")), key -> null));
    }

    @Test
    void installedModsCannotOutnumberLoadedOnes() {
        LoadedModCounts counts = new LoadedModCounts(0, 2, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> counts.withInstalled(new LoadedModCounts.Installed(0, 3, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new LoadedModCounts.Installed(0, -1, 0, 0, 0));
    }

    @Test
    void countsCannotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> new LoadedModCounts(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LoadedModCounts(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new LoadedModCounts(0, 0, 0, 0, -1));
    }

    private static Map<String, String> bundled(String language) throws IOException {
        try (InputStream in = ModCountLines.bundledLanguage(language).orElseThrow()) {
            return new Gson()
                    .fromJson(
                            new InputStreamReader(in, StandardCharsets.UTF_8),
                            new TypeToken<java.util.LinkedHashMap<String, String>>() {}.getType());
        }
    }
}
