package dev.plexusmodloader.compat.fabric;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * {@link FabricBridge#providedPackages()} tells the link check which Fabric Loader classes a mod may use. A class named
 * there must really be in the bridge (its own stand-in or one packed from Fabric Loader, see {@code build.gradle.kts}):
 * the link check treats a listed class as present, so a missing one would fail only when a mod runs.
 */
class ProvidedClassesTest {

    @Test
    void everyClassTheBridgeSaysItProvidesIsThere() {
        for (String provided : new FabricBridge().providedPackages()) {
            if (provided.endsWith("/")) {
                continue; // a package; the API packages are packed whole
            }
            // A class name, or a class and its nested classes: the class itself must exist.
            assertNotNull(
                    getClass().getClassLoader().getResource(provided + ".class"),
                    provided + " is listed as provided but is not in the bridge");
        }
    }
}
