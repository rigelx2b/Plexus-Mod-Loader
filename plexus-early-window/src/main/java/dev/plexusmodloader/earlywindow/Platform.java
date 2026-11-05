package dev.plexusmodloader.earlywindow;

import java.util.Locale;

/** The operating system, for the window process's launch options. */
enum Platform {
    WINDOWS,
    MAC,
    OTHER;

    static Platform current() {
        return fromOsName(System.getProperty("os.name", ""));
    }

    /** From {@code os.name}: {@code Windows 11}, {@code Mac OS X}; {@code Darwin} is macOS too, not Windows. */
    static Platform fromOsName(String osName) {
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("windows")) {
            return WINDOWS;
        }
        if (lower.startsWith("mac") || lower.startsWith("darwin")) {
            return MAC;
        }
        return OTHER;
    }
}
