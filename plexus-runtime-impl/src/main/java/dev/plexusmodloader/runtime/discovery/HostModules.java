package dev.plexusmodloader.runtime.discovery;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.compat.bridge.CompatibilityFinding;
import dev.plexusmodloader.runtime.discovery.ForeignModResolver.ForeignMod;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Plexus Mod Loader's own builds of Fabric API modules for a host loader (ADR-0031): a module that cannot run as written
 * on the host's patched game ({@code PLEXUS-COMPAT-224}) is replaced by the build implemented on that host's hooks, when
 * Plexus Mod Loader has one for that exact host release. The builds ship inside Plexus Mod Loader's jars as resources,
 * listed in {@value #INDEX}: a key {@code <host key>.<mod id>} ({@link #hostKey}) names the jar resource. A key {@code
 * <host key>.<mod id>.always} set to {@code true} has the build replace that module even when the module fits the
 * patch: one that loads there but does not do what it does under Fabric Loader (a test mod's lines differ). A replacement
 * is a mod of its own (no container), assessed by its bridge as any mod file is; the host check then runs on it too.
 * When several mods nest copies of one module and any copy is replaced, every copy is: one host module stands for them
 * all, as Fabric Loader keeps one copy of a nested module.
 *
 * <p>Not thread-safe; the runtime calls it once, on the launch thread.
 */
public final class HostModules {

    /** The index of host modules on the class path. */
    public static final String INDEX = "META-INF/plexusmodloader/host-modules.properties";

    private static final String BLOCKED = "PLEXUS-COMPAT-224";

    private HostModules() {}

    /**
     * The result of {@link #replaceLeftOut}.
     *
     * @param mods the mods, each replaced module once, in the place of its first copy
     * @param replaced the replacements, in the order of {@code mods}
     */
    public record Result(List<ForeignMod> mods, List<Replacement> replaced) {
        public Result {
            mods = List.copyOf(mods);
            replaced = List.copyOf(replaced);
        }
    }

    /** Why an original module is replaced. */
    public enum Reason {
        /** It does not fit the host's patch ({@code PLEXUS-COMPAT-224}). */
        LEFT_OUT,
        /** It fits, but the index marks the host module {@code always}. */
        ALWAYS,
        /** It fits, but another copy of the same module is replaced. */
        ANOTHER_COPY
    }

    /**
     * One original module replaced by a host module.
     *
     * @param original the original's jar
     * @param hostModule the host module's extracted jar
     * @param reason why the original is replaced
     */
    public record Replacement(Path original, Path hostModule, Reason reason) {}

    /**
     * {@code mods} with each one left out for not fitting {@code hostName}'s patch, and each one whose host module is
     * marked {@code always}, replaced by the host module of the same ID that {@code loader}'s index lists for that host,
     * extracted into {@code directory}.
     */
    public static Result replaceLeftOut(
            String hostName,
            List<ForeignMod> mods,
            ForeignModResolver bridges,
            GameSide side,
            Path directory,
            ClassLoader loader) {
        Properties index = index(loader);
        if (index.isEmpty()) {
            return new Result(mods, List.of());
        }
        String host = hostKey(hostName);
        // The host module of each module ID that any copy needs replaced, found before any copy is placed.
        Map<String, ForeignMod> replacements = new HashMap<>();
        for (ForeignMod mod : mods) {
            String id = mod.assessment().modId();
            String resource = index.getProperty(host + "." + id);
            if (resource == null
                    || replacements.containsKey(id)
                    || !(leftOutForTheHost(mod) || always(index, host, id))) {
                continue;
            }
            extract(loader, resource, directory)
                    .map(ModFileScanner::classify)
                    .flatMap(file -> bridges.assess(file, side))
                    .filter(found -> found.assessment().modId().equals(id))
                    .ifPresent(found -> replacements.put(id, found));
        }
        List<ForeignMod> result = new ArrayList<>();
        List<Replacement> replaced = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (ForeignMod mod : mods) {
            String id = mod.assessment().modId();
            ForeignMod replacement = replacements.get(id);
            if (replacement == null) {
                result.add(mod);
                continue;
            }
            if (placed.add(id)) {
                result.add(replacement);
            }
            Reason reason = leftOutForTheHost(mod)
                    ? Reason.LEFT_OUT
                    : always(index, host, id) ? Reason.ALWAYS : Reason.ANOTHER_COPY;
            replaced.add(new Replacement(mod.jar(), replacement.jar(), reason));
        }
        return new Result(result, replaced);
    }

    /** {@code NeoForge 26.2.0.93} becomes {@code neoforge-26.2.0.93}: the host's name as an index key. */
    static String hostKey(String hostName) {
        return hostName.strip().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    private static boolean always(Properties index, String host, String id) {
        return Boolean.parseBoolean(index.getProperty(host + "." + id + ".always"));
    }

    private static boolean leftOutForTheHost(ForeignMod mod) {
        for (CompatibilityFinding finding : mod.assessment().findings()) {
            if (finding.blocking() && finding.code().equals(BLOCKED)) {
                return true;
            }
        }
        return false;
    }

    private static Properties index(ClassLoader loader) {
        Properties index = new Properties();
        try (InputStream input = loader.getResourceAsStream(INDEX)) {
            if (input != null) {
                index.load(input);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + INDEX, e);
        }
        return index;
    }

    /** The jar resource {@code resource}, stored under its content hash so that a changed build is a new file. */
    private static Optional<Path> extract(ClassLoader loader, String resource, Path directory) {
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                return Optional.empty();
            }
            byte[] bytes = input.readAllBytes();
            String name = resource.substring(resource.lastIndexOf('/') + 1);
            Path target = directory.resolve(sha256(bytes).substring(0, 16)).resolve(name);
            if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                Files.createDirectories(target.getParent());
                Path partial = Files.createTempFile(target.getParent(), name, ".partial");
                Files.write(partial, bytes);
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            return Optional.of(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract Plexus Mod Loader's host module " + resource, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
