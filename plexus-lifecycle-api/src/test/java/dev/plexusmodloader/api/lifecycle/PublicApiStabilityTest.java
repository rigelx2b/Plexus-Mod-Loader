package dev.plexusmodloader.api.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.content.ContentRegistrar;
import dev.plexusmodloader.api.event.EventKey;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.system.SystemId;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every public top-level type of the public API modules declares its stability with {@link ApiStability}
 * (design doc section 5.4, docs/API_CATALOG.md). The modules are found through one anchor type each, so a new
 * package in an existing module is covered without changing this test.
 */
class PublicApiStabilityTest {

    private static final String API_PACKAGE_PREFIX = "dev.plexusmodloader.api.";

    @Test
    void everyPublicTopLevelTypeDeclaresItsStability() throws Exception {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Class<?> anchor :
                List.of(ApiStability.class, SystemId.class, EventKey.class, ContentRegistrar.class, ModContext.class)) {
            for (String name : topLevelTypeNames(anchor)) {
                Class<?> type = Class.forName(name, false, anchor.getClassLoader());
                if (!Modifier.isPublic(type.getModifiers())) {
                    continue;
                }
                checked++;
                if (!type.isAnnotationPresent(ApiStability.class)) {
                    missing.add(name);
                }
            }
        }
        Collections.sort(missing);
        assertEquals(List.of(), missing, "public types without @ApiStability");
        assertTrue(checked >= 100, "the scan found only " + checked + " public types, so it probably missed a module");
    }

    /** Names of the top-level classes of the API packages in the class directory or jar that holds {@code anchor}. */
    private static List<String> topLevelTypeNames(Class<?> anchor) throws IOException, URISyntaxException {
        Path location = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> entries = new ArrayList<>();
        if (Files.isDirectory(location)) {
            try (Stream<Path> files = Files.walk(location)) {
                files.filter(Files::isRegularFile)
                        .map(file -> location.relativize(file).toString().replace('\\', '/'))
                        .forEach(entries::add);
            }
        } else {
            try (JarFile jar = new JarFile(location.toFile())) {
                jar.stream().map(entry -> entry.getName()).forEach(entries::add);
            }
        }
        return entries.stream()
                .filter(entry -> entry.endsWith(".class"))
                .map(entry ->
                        entry.substring(0, entry.length() - ".class".length()).replace('/', '.'))
                .filter(name -> name.startsWith(API_PACKAGE_PREFIX))
                .filter(name -> name.indexOf('$') < 0)
                .filter(name -> !name.endsWith("package-info") && !name.endsWith("module-info"))
                .sorted()
                .toList();
    }
}
