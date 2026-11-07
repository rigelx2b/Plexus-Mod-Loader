package dev.plexusmodloader.runtime.compat;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.compat.bridge.BridgeLaunchContext;
import dev.plexusmodloader.compat.bridge.BridgedAccessWidening;
import dev.plexusmodloader.compat.bridge.CompatibilityFinding;
import dev.plexusmodloader.compat.bridge.GameClassBytes;
import dev.plexusmodloader.compat.bridge.HostedLaunch;
import dev.plexusmodloader.compat.bridge.ModLoaderBridge;
import dev.plexusmodloader.internal.transform.MethodTarget;
import dev.plexusmodloader.runtime.discovery.ForeignModResolver;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Keeps out, before the runtime decides which mods load, the other loaders' mods that cannot work as written on the
 * game a host loader patched (ADR-0030): a Fabric mod whose mixin injects at a call NeoForge's patch removed would
 * otherwise run with that part missing, and its users would report the breakage to the mod's author, whose mixin is
 * correct for the game it was written for. Each bridge says which of its mods do not fit ({@link
 * ModLoaderBridge#hostIncompatibilities}); each such mod gets a blocking {@code PLEXUS-COMPAT-224} finding that names
 * Plexus Mod Loader as the one to fix it, and the dependency check then leaves out the mods that need it ({@code 114}).
 *
 * <p>The host's classes are read from its jars (the patched game, the jars its loader defines later), and the JDK's and
 * libraries' from Plexus Mod Loader's class path: no class is defined. Not thread-safe; the runtime calls it once, on the
 * launch thread.
 */
public final class HostPatchCheck {

    /** The code of a mod left out because it does not fit the host's patch. */
    public static final String CODE = "PLEXUS-COMPAT-224";

    /**
     * Code of the non-blocking finding when Plexus Mod Loader makes a host-final method overridable again (ADR-0047).
     */
    public static final String OPENED_CODE = "PLEXUS-COMPAT-228";

    private HostPatchCheck() {}

    /**
     * {@code mods}, adding blocking {@link #CODE} where a bridge rejects {@code host}'s patch and opening no final
     * method.
     *
     * @param hostJars the jars that hold the host's patched game and its loader's classes
     * @param fallback where the JDK's and the libraries' classes are read from
     */
    public static List<ForeignModResolver.ForeignMod> apply(
            GameSide side,
            HostedLaunch host,
            List<Path> hostJars,
            ClassLoader fallback,
            List<ModLoaderBridge> bridges,
            List<ForeignModResolver.ForeignMod> mods) {
        return apply(side, host, List.of(), hostJars, fallback, bridges, mods);
    }

    /**
     * As the overload without {@code overridableFinalMethods}, and also checks loadable mods for classes that override
     * a method the host's patch made {@code final} while vanilla does not ({@link HostFinalOverrides}), which the JVM
     * refuses to define. When that method is in {@code overridableFinalMethods} (reviewed for the host, {@link
     * dev.plexusmodloader.compat.bridge.GameHost#overridableFinalMethods()}), the mod gets an extendable access
     * widening of exactly that method and a non-blocking {@link #OPENED_CODE} finding, and loads (ADR-0047). Otherwise
     * the mod gets the blocking {@link #CODE} finding with that reason, leaving it out instead of stopping the game.
     * Calling this method again on the result adds nothing twice.
     *
     * @param overridableFinalMethods the methods the host's patch made {@code final} that mods may override as on
     *     vanilla
     * @param hostJars the jars that hold the host's patched game and its loader's classes
     * @param fallback where the JDK's and the libraries' classes are read from
     * @throws java.io.UncheckedIOException when a mod jar, the host's jars, or the vanilla game jar cannot be read
     */
    public static List<ForeignModResolver.ForeignMod> apply(
            GameSide side,
            HostedLaunch host,
            List<MethodTarget> overridableFinalMethods,
            List<Path> hostJars,
            ClassLoader fallback,
            List<ModLoaderBridge> bridges,
            List<ForeignModResolver.ForeignMod> mods) {
        if (!host.isHosted()) {
            return mods;
        }
        List<BridgeLaunchContext.LoadedMod> candidates = new ArrayList<>();
        for (ForeignModResolver.ForeignMod mod : mods) {
            if (mod.assessment().status().loadable()) {
                candidates.add(new BridgeLaunchContext.LoadedMod(
                        mod.assessment().loader(),
                        mod.assessment().modId(),
                        mod.assessment().version(),
                        mod.assessment().modId(),
                        mod.assessment().providedIds(),
                        mod.jar(),
                        mod.containerJar(),
                        mod.nestedEntry()));
            }
        }
        if (candidates.isEmpty()) {
            return mods;
        }
        Map<Path, List<String>> reasons = new LinkedHashMap<>();
        Map<Path, List<HostFinalOverrides.FinalOverride>> opened = new LinkedHashMap<>();
        try (JarClasses classes = new JarClasses(hostJars, fallback);
                VanillaClasses vanilla = new VanillaClasses(host.vanillaGameJar())) {
            for (ModLoaderBridge bridge : bridges) {
                bridge.hostIncompatibilities(side, host, candidates, classes)
                        .forEach((jar, lines) -> reasons.computeIfAbsent(jar, key -> new ArrayList<>())
                                .addAll(lines));
            }
            // A mod class that overrides a method the host's patch made final fails to load
            // (IncompatibleClassChangeError).
            HostFinalOverrides finals = new HostFinalOverrides(classes, vanilla::classBytes);
            Set<MethodTarget> reviewed = Set.copyOf(overridableFinalMethods);
            Set<Path> checkedJars = new HashSet<>();
            for (BridgeLaunchContext.LoadedMod candidate : candidates) {
                if (!checkedJars.add(candidate.jar())) {
                    continue;
                }
                for (HostFinalOverrides.FinalOverride found : finals.find(candidate.jar())) {
                    if (reviewed.contains(found.method())) {
                        opened.computeIfAbsent(candidate.jar(), key -> new ArrayList<>())
                                .add(found);
                    } else {
                        reasons.computeIfAbsent(candidate.jar(), key -> new ArrayList<>())
                                .add(String.format(
                                        Locale.ROOT,
                                        "%s: it overrides %s, which the patch of %s made final (the vanilla one is not), and the JVM refuses to load such a class",
                                        simpleName(found.modClass()),
                                        found.method(),
                                        host.hostName()));
                    }
                }
            }
        }
        if (reasons.isEmpty() && opened.isEmpty()) {
            return mods;
        }
        List<ForeignModResolver.ForeignMod> checked = new ArrayList<>();
        for (ForeignModResolver.ForeignMod mod : mods) {
            if (!mod.assessment().status().loadable()) {
                checked.add(mod);
                continue;
            }
            List<String> lines = reasons.get(mod.jar());
            if (lines != null) {
                checked.add(mod.withFindings(List.of(finding(host.hostName(), lines))));
                continue;
            }
            List<HostFinalOverrides.FinalOverride> overrides = opened.get(mod.jar());
            checked.add(overrides == null ? mod : opened(mod, host.hostName(), overrides));
        }
        return checked;
    }

    /**
     * {@code mod} with missing extendable widenings from {@code overrides} and {@link #OPENED_CODE} naming them, or
     * {@code mod} itself.
     */
    private static ForeignModResolver.ForeignMod opened(
            ForeignModResolver.ForeignMod mod, String hostName, List<HostFinalOverrides.FinalOverride> overrides) {
        List<BridgedAccessWidening> widenings = new ArrayList<>(mod.assessment().accessWidenings());
        Map<MethodTarget, List<String>> classesByMethod = new LinkedHashMap<>();
        for (HostFinalOverrides.FinalOverride found : overrides) {
            classesByMethod
                    .computeIfAbsent(found.method(), key -> new ArrayList<>())
                    .add(simpleName(found.modClass()));
        }
        List<String> added = new ArrayList<>();
        classesByMethod.forEach((method, classes) -> {
            BridgedAccessWidening widening = new BridgedAccessWidening(
                    OPENED_CODE,
                    method.ownerInternalName(),
                    BridgedAccessWidening.Kind.METHOD,
                    method.name(),
                    method.descriptor(),
                    Set.of(BridgedAccessWidening.Access.EXTENDABLE));
            boolean present = widenings.stream()
                    .anyMatch(existing -> existing.owner().equals(widening.owner())
                            && existing.kind() == widening.kind()
                            && existing.name().equals(widening.name())
                            && existing.descriptor().equals(widening.descriptor())
                            && existing.access().containsAll(widening.access()));
            if (!present) {
                widenings.add(widening);
                added.add(String.format(Locale.ROOT, "%s (overridden by %s)", method, String.join(", ", classes)));
            }
        });
        if (added.isEmpty()) {
            return mod;
        }
        CompatibilityFinding finding = new CompatibilityFinding(
                OPENED_CODE,
                false,
                "link check",
                String.format(
                        Locale.ROOT,
                        "The patch of %s made these Minecraft methods final although vanilla Minecraft does not. Plexus Mod Loader makes only these reviewed methods overridable again so the mod loads: %s (ADR-0047)",
                        hostName,
                        String.join("; ", added)));
        ForeignModResolver.ForeignMod widened = new ForeignModResolver.ForeignMod(
                mod.jar(),
                mod.bridge(),
                mod.assessment().withAccessWidenings(List.copyOf(widenings)),
                mod.containerJar(),
                mod.nestedEntry());
        return widened.withFindings(List.of(finding));
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    /** The finding for a mod whose code does not fit, with the bridge's {@code lines}. */
    static CompatibilityFinding finding(String hostName, List<String> lines) {
        return new CompatibilityFinding(
                CODE,
                true,
                "mixins",
                "Plexus Mod Loader cannot run " + (lines.size() == 1 ? "a part" : lines.size() + " parts")
                        + " of this mod as written on the game as " + hostName + " patches it, and does not run the"
                        + " mod with them left out. This is a limitation of Plexus Mod Loader, not a bug in the mod:"
                        + " report it to Plexus Mod Loader, not to the mod's author. " + String.join(" | ", lines));
    }

    /** Class files of the vanilla game jar, opened on the first read; none when there is no such jar. */
    private static final class VanillaClasses implements AutoCloseable {

        private final Path path;
        private JarFile jar;

        VanillaClasses(Path path) {
            this.path = path;
        }

        Optional<byte[]> classBytes(String internalName) {
            if (path == null || !Files.isRegularFile(path)) {
                return Optional.empty();
            }
            try {
                if (jar == null) {
                    jar = new JarFile(path.toFile());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                        String.format(
                                Locale.ROOT,
                                "Failed to open the vanilla game jar %s to compare it with the patch of the host",
                                path),
                        e);
            }
            return HostFinalOverrides.readClass(jar, internalName);
        }

        @Override
        public void close() {
            if (jar != null) {
                try {
                    jar.close();
                } catch (IOException e) {
                    // A read-only jar opened for this check; nothing was written to it.
                }
            }
        }
    }

    /** Class files from jars, then from a class loader's resources. Never defines a class. */
    private static final class JarClasses implements GameClassBytes, AutoCloseable {

        private final List<JarFile> jars = new ArrayList<>();
        private final ClassLoader fallback;

        JarClasses(List<Path> paths, ClassLoader fallback) {
            this.fallback = fallback;
            try {
                for (Path path : paths) {
                    jars.add(new JarFile(path.toFile()));
                }
            } catch (IOException e) {
                close();
                throw new UncheckedIOException("Failed to open the host's jar to check other loaders' mods", e);
            }
        }

        @Override
        public Optional<byte[]> classBytes(String className) {
            String entryName = className.replace('.', '/') + ".class";
            try {
                for (JarFile jar : jars) {
                    ZipEntry entry = jar.getEntry(entryName);
                    if (entry != null) {
                        try (InputStream input = jar.getInputStream(entry)) {
                            return Optional.of(input.readAllBytes());
                        }
                    }
                }
                try (InputStream input = fallback.getResourceAsStream(entryName)) {
                    return input == null ? Optional.empty() : Optional.of(input.readAllBytes());
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + entryName + " to check other loaders' mods", e);
            }
        }

        @Override
        public boolean isDefined(String className) {
            return false;
        }

        @Override
        public void close() {
            for (JarFile jar : jars) {
                try {
                    jar.close();
                } catch (IOException e) {
                    // Read-only jars opened for this check; nothing was written to them.
                }
            }
        }
    }
}
