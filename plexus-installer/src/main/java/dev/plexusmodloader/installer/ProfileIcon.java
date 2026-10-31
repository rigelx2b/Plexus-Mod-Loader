package dev.plexusmodloader.installer;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * The Plexus Mod Loader logo on its dark tile (the website's favicon, {@code site/static/img/favicon.svg}) drawn for the
 * launcher profile, and the logo without a background ({@link #mark}) for the installer's window and tray icons and
 * beside its title: three chevrons around a cut hexagonal core, in the logo's 512 x 512 coordinates.
 */
final class ProfileIcon {

    private ProfileIcon() {}

    private static final Color TILE = new Color(0x0B0F19);

    static BufferedImage image(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.scale(size / 512.0, size / 512.0);
            g.setColor(TILE);
            g.fill(new RoundRectangle2D.Double(0, 0, 512, 512, 230, 230));
            // The favicon's translate(-93.48, -110.55) scale(1.3652): the logo fills the tile but for 4% each side.
            g.translate(20, 20);
            fitted(g, 472);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * The logo without its tile, as large as fits {@code size} pixels square: the installer's window and tray icons and
     * the mark beside its title.
     */
    static BufferedImage mark(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            fitted(g, size);
        } finally {
            g.dispose();
        }
        return image;
    }

    // The logo's bounds with its strokes in its 512 x 512 coordinates.
    private static final double LOGO_X = 101;
    private static final double LOGO_Y = 106;
    private static final double LOGO_WIDTH = 310;
    private static final double LOGO_HEIGHT = 325;

    /** Draws the logo centred in a square of {@code size} at the origin, as large as fits with 3% to spare each side. */
    private static void fitted(Graphics2D g, double size) {
        double scale = size * 0.94 / LOGO_HEIGHT;
        Graphics2D fit = (Graphics2D) g.create();
        try {
            fit.translate(
                    (size - LOGO_WIDTH * scale) / 2 - LOGO_X * scale,
                    (size - LOGO_HEIGHT * scale) / 2 - LOGO_Y * scale);
            fit.scale(scale, scale);
            drawLogo(fit);
        } finally {
            fit.dispose();
        }
    }

    /** The logo without its tile, in 512 x 512 coordinates. */
    private static void drawLogo(Graphics2D g) {
        g.setStroke(new BasicStroke(50, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        wing(g, new double[] {212.7, 131, 126.1, 181, 126.1, 281}, 212.7, 106, 126.1, 281, 0x22D3EE, 0x0284C7);
        wing(g, new double[] {299.3, 131, 385.9, 181, 385.9, 281}, 299.3, 106, 385.9, 281, 0xC084FC, 0x6366F1);
        wing(g, new double[] {169.4, 356, 256, 406, 342.6, 356}, 169.4, 356, 342.6, 406, 0x818CF8, 0x4F46E5);
        Path2D core = polygon(256, 176, 325, 216, 325, 296, 256, 336, 187, 296, 187, 216);
        g.setPaint(new LinearGradientPaint(
                new Point2D.Double(187, 176), new Point2D.Double(325, 336), new float[] {0f, 0.5f, 1f}, new Color[] {
                    new Color(0x22D3EE), new Color(0x818CF8), new Color(0x4F46E5)
                }));
        g.fill(core);
        g.setStroke(new BasicStroke(8, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(core);
        facet(g, polygon(256, 176, 325, 216, 256, 256), Color.WHITE, 0.28f);
        facet(g, polygon(256, 336, 187, 296, 256, 256), new Color(0x1E1B4B), 0.32f);
    }

    private static void wing(
            Graphics2D g, double[] points, double x1, double y1, double x2, double y2, int from, int to) {
        Path2D line = new Path2D.Double();
        line.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) {
            line.lineTo(points[i], points[i + 1]);
        }
        g.setPaint(new LinearGradientPaint(
                new Point2D.Double(x1, y1), new Point2D.Double(x2, y2), new float[] {0f, 1f}, new Color[] {
                    new Color(from), new Color(to)
                }));
        g.draw(line);
    }

    private static void facet(Graphics2D g, Path2D shape, Color color, float opacity) {
        Graphics2D facet = (Graphics2D) g.create();
        try {
            facet.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
            facet.setColor(color);
            facet.fill(shape);
            facet.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            facet.draw(shape);
        } finally {
            facet.dispose();
        }
    }

    private static Path2D polygon(double... points) {
        Path2D shape = new Path2D.Double();
        shape.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) {
            shape.lineTo(points[i], points[i + 1]);
        }
        shape.closePath();
        return shape;
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
}
