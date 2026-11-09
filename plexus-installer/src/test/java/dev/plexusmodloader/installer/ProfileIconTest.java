package dev.plexusmodloader.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ProfileIconTest {

    @Test
    void theProfileIconIsTheTileAt128() throws IOException {
        String uri = ProfileIcon.dataUri();
        assertTrue(uri.startsWith("data:image/png;base64,"));
        BufferedImage icon = ImageIO.read(
                new ByteArrayInputStream(Base64.getDecoder().decode(uri.substring("data:image/png;base64,".length()))));
        assertNotNull(icon);
        assertEquals(128, icon.getWidth());
        assertEquals(128, icon.getHeight());
        // The tile's rounded corner is transparent, its middle is drawn.
        assertEquals(0, icon.getRGB(0, 0) >>> 24);
        assertEquals(255, icon.getRGB(64, 64) >>> 24);
    }

    @Test
    void theMarkFillsEachIconSize() {
        for (int size : new int[] {16, 32, 40, 48, 64, 256}) {
            BufferedImage mark = ProfileIcon.mark(size);
            assertEquals(size, mark.getWidth());
            assertEquals(size, mark.getHeight());
            assertNotEquals(0, mark.getRGB(size / 2, size / 2) >>> 24, "nothing drawn in the middle at " + size);
            assertEquals(0, mark.getRGB(0, 0) >>> 24, "no background at " + size);
        }
    }

    @Test
    void largeMarksKeepSquarePixels() {
        // 256: the 26 x 28 logo 8 times its size, each logo pixel an 8 x 8 block of one colour.
        BufferedImage mark = ProfileIcon.mark(256);
        int x = 128 - 128 % 8;
        int y = 128 - 128 % 8;
        int colour = mark.getRGB(x, y);
        boolean same = true;
        for (int dy = 0; dy < 8 && same; dy++) {
            for (int dx = 0; dx < 8 && same; dx++) {
                same = mark.getRGB(x + dx, y + dy) == colour;
            }
        }
        assertTrue(same, "the logo's pixels are not 8 x 8 blocks at 256");
    }
}
