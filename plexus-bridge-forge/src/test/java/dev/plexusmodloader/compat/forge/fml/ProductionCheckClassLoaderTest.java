package dev.plexusmodloader.compat.forge.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-0038: FML 10 must not find the game through the parent class loader, or it runs as a development setup. */
class ProductionCheckClassLoaderTest {

    @Test
    void onlyAnFmlBeforeElevenAsksTheParentForTheGame() {
        assertTrue(ProductionCheckClassLoader.needed("10.0"));
        assertTrue(ProductionCheckClassLoader.needed("4.0"));
        assertFalse(ProductionCheckClassLoader.needed("11.0"));
        assertFalse(ProductionCheckClassLoader.needed("12.0"));
        assertFalse(ProductionCheckClassLoader.needed(null));
        assertFalse(ProductionCheckClassLoader.needed("unknown"));
    }

    @Test
    void theGamesVersionClassIsHiddenAndEverythingElseIsTheParents(@TempDir Path folder) throws IOException {
        Path detected = folder.resolve("net/minecraft/DetectedVersion.class");
        Path other = folder.resolve("net/minecraft/SharedConstants.class");
        Files.createDirectories(detected.getParent());
        Files.write(detected, new byte[] {1});
        Files.write(other, new byte[] {2});

        try (URLClassLoader parent =
                new URLClassLoader(new URL[] {folder.toUri().toURL()}, null)) {
            ProductionCheckClassLoader child = new ProductionCheckClassLoader(parent);

            assertNotNull(parent.getResource(ProductionCheckClassLoader.DETECTED_VERSION));
            assertNull(child.getResource(ProductionCheckClassLoader.DETECTED_VERSION));
            assertEquals(
                    parent.getResource("net/minecraft/SharedConstants.class"),
                    child.getResource("net/minecraft/SharedConstants.class"));
            assertSame(parent, child.getParent());
        }
    }
}
