package dev.plexusmodloader.compat.fabric.intermediary;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;

/**
 * The class hierarchy of the remapped game jar, in intermediary terms, for {@link IntermediaryTranslation} to break the
 * ties between members that Fabric numbered the same (ADR-0037). The jar's class headers are read the first time a
 * tie needs them, not before; a launch whose mods have no tie never reads them.
 *
 * <p>Thread: safe from any thread; the headers are read once.
 */
final class GameParents implements IntermediaryTranslation.Parents {

    private final Path gameJar;
    private final IntermediaryTranslation translation;
    private volatile Map<String, List<String>> headers;

    GameParents(Path gameJar, IntermediaryTranslation translation) {
        this.gameJar = gameJar;
        this.translation = translation;
    }

    @Override
    public List<String> of(String intermediaryClass) {
        String game = translation.mapClass(intermediaryClass);
        List<String> parents = headers().get(game);
        if (parents == null) {
            return List.of();
        }
        List<String> intermediary = new ArrayList<>(parents.size());
        for (String parent : parents) {
            intermediary.add(translation.unmapClass(parent));
        }
        return intermediary;
    }

    private Map<String, List<String>> headers() {
        Map<String, List<String>> read = headers;
        if (read == null) {
            synchronized (this) {
                read = headers;
                if (read == null) {
                    read = readHeaders();
                    headers = read;
                }
            }
        }
        return read;
    }

    private Map<String, List<String>> readHeaders() {
        Map<String, List<String>> map = new HashMap<>(16384);
        try (JarFile jar = new JarFile(gameJar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(jar.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(input);
                    List<String> parents = new ArrayList<>();
                    if (reader.getSuperName() != null) {
                        parents.add(reader.getSuperName());
                    }
                    parents.addAll(List.of(reader.getInterfaces()));
                    map.put(reader.getClassName(), parents);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the class hierarchy of " + gameJar, e);
        }
        return map;
    }
}
