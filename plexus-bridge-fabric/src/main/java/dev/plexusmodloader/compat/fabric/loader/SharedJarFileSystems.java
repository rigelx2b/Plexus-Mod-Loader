package dev.plexusmodloader.compat.fabric.loader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The zip file systems of mod jars that the Fabric and Quilt containers keep open for the rest of the game, one per jar.
 *
 * <p>{@code FileSystems.newFileSystem(Path)} makes a new zip file system on every call, and each one holds a copy of the
 * jar's central directory and an index node per entry until it is closed. A container opened its jar's file system once,
 * but the Quilt containers of nested mods opened the <em>outer</em> jar again for every nested mod (Fabric API's jar has
 * dozens), and a jar can be both a container's root and another's outer jar. Here a jar is opened once and every
 * container gets the same root. Nothing is closed: paths handed to mods must stay valid, as under Fabric Loader.
 *
 * <p>Thread: safe from any thread.
 */
public final class SharedJarFileSystems {

    private static final ConcurrentHashMap<Path, FileSystem> OPEN = new ConcurrentHashMap<>();

    private SharedJarFileSystems() {}

    /**
     * The root directory of {@code jar} as a zip file system, opened the first time and shared afterwards.
     *
     * @throws UncheckedIOException when the jar cannot be opened as a zip file
     */
    public static Path root(Path jar) {
        return fileSystem(jar).getRootDirectories().iterator().next();
    }

    /** The shared zip file system of {@code jar}. */
    public static FileSystem fileSystem(Path jar) {
        Path key = jar.toAbsolutePath().normalize();
        return OPEN.computeIfAbsent(key, path -> {
            try {
                return FileSystems.newFileSystem(path);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot open " + path, e);
            }
        });
    }

    /** How many jars are open; for tests and diagnostics. */
    public static int openCount() {
        return OPEN.size();
    }
}
