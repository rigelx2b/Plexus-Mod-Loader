package dev.plexusmodloader.installer;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * The Plexus Mod Loader logo, pixel art: on its dark tile ({@code plexus_logo_tile.png}, 128 x 128) for the launcher
 * profile, and without a background ({@code plexus_logo.png}, 32 x 32, {@link #mark}) for the installer's window and
 * tray icons and beside its title. Both are beside this class, written by {@code tools/logo/LogoArt.java}.
 */
final class ProfileIcon {

    private ProfileIcon() {}

    private static final BufferedImage LOGO = crop(read("plexus_logo.png"));
    private static final BufferedImage TILE = read("plexus_logo_tile.png");

    /** The logo on its tile, {@code size} pixels square. */
    static BufferedImage image(int size) {
        return size == TILE.getWidth() ? TILE : smooth(TILE, size, size);
    }

    /**
     * The logo without its tile, as large as fits {@code size} pixels square with 3% to spare each side: a whole number
     * of times its size while that fills four fifths of the room (so its pixels stay square), else smoothly reduced from
     * 16 times its size.
     */
    static BufferedImage mark(int size) {
        double room = Math.min(size * 0.94 / LOGO.getWidth(), size * 0.94 / LOGO.getHeight());
        int whole = (int) Math.floor(room);
        BufferedImage drawn = whole >= 1 && whole >= room * 0.8
                ? nearest(LOGO, whole)
                : smooth(nearest(LOGO, 16), (int) Math.round(LOGO.getWidth() * room), (int)
                        Math.round(LOGO.getHeight() * room));
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.drawImage(drawn, (size - drawn.getWidth()) / 2, (size - drawn.getHeight()) / 2, null);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** A {@code data:} URI of the 128 x 128 icon, the format {@code launcher_profiles.json} keeps icons in. */
    static String dataUri() {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(image(128), "png", png);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode the profile icon", e);
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
    }

    private static BufferedImage read(String name) {
        try (InputStream in = ProfileIcon.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing installer resource " + name);
            }
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IllegalStateException("Unreadable installer resource " + name);
            }
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read installer resource " + name, e);
        }
    }

    /** The image cut to the box of its non-transparent pixels. */
    private static BufferedImage crop(BufferedImage source) {
        int x0 = source.getWidth();
        int y0 = source.getHeight();
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                if ((source.getRGB(x, y) >>> 24) != 0) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? source : source.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    private static BufferedImage nearest(BufferedImage source, int times) {
        BufferedImage image =
                new BufferedImage(source.getWidth() * times, source.getHeight() * times, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), null);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage smooth(BufferedImage source, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.drawImage(source.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        } finally {
            g.dispose();
        }
        return image;
    }
}
