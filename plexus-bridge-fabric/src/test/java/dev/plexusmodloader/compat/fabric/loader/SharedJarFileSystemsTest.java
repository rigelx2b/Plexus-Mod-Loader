package dev.plexusmodloader.compat.fabric.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedJarFileSystemsTest {

    @TempDir
    Path folder;

    private Path jar(String name, String entry, String text) throws IOException {
        Path jar = folder.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }

    @Test
    void sameJarGivesTheSameFileSystemWhateverTheSpelling() throws IOException {
        Path jar = jar("mod.jar", "fabric.mod.json", "{}");
        Path sibling = folder.resolve("other").resolve("..").resolve("mod.jar");
        Files.createDirectories(folder.resolve("other"));

        assertSame(SharedJarFileSystems.fileSystem(jar), SharedJarFileSystems.fileSystem(sibling));
        assertEquals("{}", Files.readString(SharedJarFileSystems.root(jar).resolve("fabric.mod.json")));
    }

    @Test
    void differentJarsGetTheirOwnFileSystems() throws IOException {
        Path first = jar("a.jar", "a.txt", "a");
        Path second = jar("b.jar", "b.txt", "b");

        assertTrue(SharedJarFileSystems.fileSystem(first) != SharedJarFileSystems.fileSystem(second));
        assertEquals("b", Files.readString(SharedJarFileSystems.root(second).resolve("b.txt")));
    }

    @Test
    void aFileThatIsNotAZipFails() throws IOException {
        Path notZip = folder.resolve("broken.jar");
        Files.writeString(notZip, "this is not a zip file");

        assertThrows(UncheckedIOException.class, () -> SharedJarFileSystems.root(notZip));
    }

    @Test
    void theFileSystemStaysOpenForPathsHandedToMods() throws IOException {
        Path jar = jar("kept.jar", "data.txt", "kept");
        Path root = SharedJarFileSystems.root(jar);
        int before = SharedJarFileSystems.openCount();

        SharedJarFileSystems.root(jar);

        assertEquals(before, SharedJarFileSystems.openCount());
        assertTrue(root.getFileSystem().isOpen());
        assertEquals("kept", Files.readString(root.resolve("data.txt")));
    }
}
