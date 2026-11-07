package dev.plexusmodloader.compat.forge.modlauncher;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The mod jars the host hands to FML 4 in a system property, separated by {@link File#pathSeparator}. */
public final class ModPathList {

    private ModPathList() {}

    /** The absolute, normalized paths of {@code property}, in order; blank entries and a missing property give none. */
    public static List<Path> parse(String property) {
        List<Path> paths = new ArrayList<>();
        if (property != null) {
            for (String entry : property.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    paths.add(Path.of(entry).toAbsolutePath().normalize());
                }
            }
        }
        return List.copyOf(paths);
    }
}
