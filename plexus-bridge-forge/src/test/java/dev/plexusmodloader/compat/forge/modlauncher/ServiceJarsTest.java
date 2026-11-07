package dev.plexusmodloader.compat.forge.modlauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ADR-0043: the jars of the SERVICE layer are not offered to FML as mods a second time. */
class ServiceJarsTest {

    @AfterEach
    void forget() {
        ServiceJars.clear();
    }

    @Test
    void recordedJarsAreLeftOutInOrder() {
        ServiceJars.record(Path.of("mods/service.jar"));

        assertEquals(
                List.of(Path.of("mods/a.jar"), Path.of("mods/b.jar")),
                ServiceJars.without(
                        List.of(Path.of("mods/a.jar"), Path.of("mods/../mods/service.jar"), Path.of("mods/b.jar"))));
    }

    @Test
    void withoutRecordedJarsEverythingStays() {
        List<Path> jars = List.of(Path.of("mods/a.jar"));

        assertEquals(jars, ServiceJars.without(jars));
    }
}
