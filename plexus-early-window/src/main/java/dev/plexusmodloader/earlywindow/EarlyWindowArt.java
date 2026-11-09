package dev.plexusmodloader.earlywindow;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * The early window's images: Plexus Mod Loader's cube ({@code plexus_cube.png}, the same sheet as the game layers' loading
 * screen marks) and window icon ({@code plexus_icon.png}), both beside this class, pixel art drawn by
 * the Aseprite scripts in {@code tools/logo/pixel/}, and the game's own Mojang Studios logo, read from the game's jar.
 */
final class EarlyWindowArt {

    private EarlyWindowArt() {}

    /** The cube's 32 x 32 frames from a sheet of frames stacked top to bottom; empty when it cannot be read. */
    static List<BufferedImage> cube() {
        return image("plexus_cube.png").map(EarlyWindowArt::frames).orElse(List.of());
    }

    static List<BufferedImage> frames(BufferedImage sheet) {
        int size = LoadingScreenLayout.CUBE;
        if (sheet.getWidth() != size || sheet.getHeight() % size != 0) {
            return List.of();
        }
        List<BufferedImage> frames = new ArrayList<>();
        for (int top = 0; top < sheet.getHeight(); top += size) {
            frames.add(sheet.getSubimage(0, top, size, size));
        }
        return List.copyOf(frames);
    }

    /** The sizes Windows asks a window for: title bar, taskbar and Alt+Tab, at display scales up to 200%. */
    private static final int[] ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    /**
     * The window icon ({@code plexus_icon.png}, 256 x 256) at each of {@link #ICON_SIZES}, so the system never scales a
     * single image itself; empty when it cannot be read.
     */
    static List<BufferedImage> icons() {
        return image("plexus_icon.png").map(EarlyWindowArt::iconSizes).orElse(List.of());
    }

    static List<BufferedImage> iconSizes(BufferedImage icon) {
        List<BufferedImage> sizes = new ArrayList<>();
        for (int size : ICON_SIZES) {
            sizes.add(scaled(icon, size));
        }
        return List.copyOf(sizes);
    }

    // Halves the image until it is less than twice the size, so bilinear steps keep the thin edges smooth.
    private static BufferedImage scaled(BufferedImage image, int size) {
        BufferedImage current = image;
        while (current.getWidth() >= 2 * size) {
            current = draw(current, current.getWidth() / 2);
        }
        return current.getWidth() == size ? current : draw(current, size);
    }

    private static BufferedImage draw(BufferedImage image, int size) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    /** The game's logo, {@code entry} in {@code gameJar}; empty when either is missing or unreadable. */
    static Optional<BufferedImage> logo(Optional<Path> gameJar, Optional<String> entry) {
        if (gameJar.isEmpty() || entry.isEmpty()) {
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(gameJar.get().toFile())) {
            ZipEntry found = zip.getEntry(entry.get());
            if (found == null) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(found)) {
                return Optional.ofNullable(ImageIO.read(in));
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<BufferedImage> image(String name) {
        try (InputStream in = EarlyWindowArt.class.getResourceAsStream(name)) {
            return in == null ? Optional.empty() : Optional.ofNullable(ImageIO.read(in));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
