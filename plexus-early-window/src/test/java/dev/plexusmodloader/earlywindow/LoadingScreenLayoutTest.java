package dev.plexusmodloader.earlywindow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoadingScreenLayoutTest {

    @Test
    void automaticScaleIsTheGamesForCommonSizes() {
        assertEquals(2, LoadingScreenLayout.scale(854, 480, 0));
        assertEquals(4, LoadingScreenLayout.scale(1920, 1080, 0));
        assertEquals(1, LoadingScreenLayout.scale(640, 400, 0));
        assertEquals(3, LoadingScreenLayout.scale(1280, 720, 0));
    }

    @Test
    void scaleOptionCapsTheAutomaticScale() {
        assertEquals(2, LoadingScreenLayout.scale(1920, 1080, 2));
        assertEquals(4, LoadingScreenLayout.scale(1920, 1080, 6));
    }

    @Test
    void guiSizeRoundsUpAsTheGameDoes() {
        LoadingScreenLayout layout = LoadingScreenLayout.of(855, 481, 2);
        assertEquals(428, layout.guiWidth());
        assertEquals(241, layout.guiHeight());
    }

    @Test
    void piecesSitWhereTheGameAndThePlexusMarksPutThem() {
        // 854 x 480 at scale 2: 427 x 240 GUI pixels; logo height min(427 * 0.75, 240) * 0.25 = 60.
        LoadingScreenLayout layout = LoadingScreenLayout.of(854, 480, 0);
        assertEquals(2, layout.scale());
        assertArrayEquals(new int[] {93, 90, 120, 60}, layout.logoLeft());
        assertArrayEquals(new int[] {213, 90, 120, 60}, layout.logoRight());
        // The bar's centre is (int) (240 * 0.8325) = 199.
        assertArrayEquals(new int[] {93, 194, 333, 204}, layout.progressBar());
        assertArrayEquals(new int[] {389, 192}, layout.cube());
        int nameWidth = PixelLetters.width(LoadingScreenState.NAME);
        assertEquals(101, nameWidth);
        assertArrayEquals(new int[] {320, 228}, layout.name(nameWidth));
        assertArrayEquals(new int[] {(427 - 50) / 2, 209}, layout.underBar(0, 50));
        assertArrayEquals(new int[] {(427 - 50) / 2, 220}, layout.underBar(1, 50));
    }

    /** Two warning lines fit between the logo and the bar, clear of the bottom row's status and name. */
    @Test
    void warningLinesSitBetweenTheLogoAndTheBar() {
        LoadingScreenLayout layout = LoadingScreenLayout.of(854, 480, 0);
        int[] bar = layout.progressBar();
        int[] logo = layout.logoLeft();

        int[] first = layout.aboveBar(0, 2, 50);
        int[] second = layout.aboveBar(1, 2, 50);
        assertArrayEquals(new int[] {(427 - 50) / 2, 173}, first);
        assertArrayEquals(new int[] {(427 - 50) / 2, 183}, second);
        assertTrue(first[1] > logo[1] + logo[3], "under the logo");
        assertTrue(second[1] + PixelLetters.HEIGHT < bar[1], "above the bar");
    }
}
