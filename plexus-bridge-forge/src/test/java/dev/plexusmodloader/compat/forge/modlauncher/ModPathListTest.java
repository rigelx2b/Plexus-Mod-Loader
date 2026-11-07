package dev.plexusmodloader.compat.forge.modlauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The mod jars the host hands to FML 4's locator in a system property (ADR-0043). */
class ModPathListTest {

    @Test
    void thePathsAreAbsoluteInOrderAndBlankEntriesAreLeftOut() {
        String property = String.join(File.pathSeparator, "mods/a.jar", "", " ", "mods/../mods/b.jar");

        assertEquals(
                List.of(
                        Path.of("mods/a.jar").toAbsolutePath().normalize(),
                        Path.of("mods/b.jar").toAbsolutePath().normalize()),
                ModPathList.parse(property));
    }

    @Test
    void withoutThePropertyThereAreNoMods() {
        assertEquals(List.of(), ModPathList.parse(null));
        assertEquals(List.of(), ModPathList.parse(""));
    }
}
