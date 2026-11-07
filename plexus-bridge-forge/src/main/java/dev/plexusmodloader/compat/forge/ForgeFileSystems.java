package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.compat.bridge.SystemClassPath;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Forge's file systems on the JVM class path (ADR-0024). FML reads mod files through file systems that Forge's {@code
 * securemodules} ({@code union}), {@code JarJarFileSystems} ({@code jij}, {@code path}) and {@code roimfs} provide, and
 * {@code unsafe} that securemodules uses. Java finds file systems and URL handlers only through the system class loader,
 * so these four jars must be there; Forge's own launcher puts them on its class path too.
 *
 * <p>Development runs have them on the class path from the start. An installed Plexus Mod Loader (launcher profile,
 * dedicated server) appends them through its Java agent ({@link SystemClassPath}), only when Forge mods are present, after
 * {@link ForgeInstallation} has downloaded them. The JVM lists its file systems once, the first time anything asks
 * ({@link FileSystemProvider#installedProviders()}): nothing may ask before the jars are appended, and this class asks
 * only afterwards, to check.
 *
 * <p>Thread: the launching thread.
 */
final class ForgeFileSystems {

    /** {@code group:artifact} of the four jars, in the order they are appended. */
    static final List<String> ARTIFACTS = List.of(
            "net.minecraftforge:securemodules",
            "cpw.mods:securejarhandler",
            "net.minecraftforge:unsafe",
            "net.minecraftforge:JarJarFileSystems",
            "net.minecraftforge:roimfs");

    /**
     * The schemes of the file systems they provide ({@code getScheme()} of the providers their {@code
     * META-INF/services/java.nio.file.spi.FileSystemProvider} names, checked with {@code javap} in the jars of Forge
     * 26.2-65.1.5 and 26.1-62.0.9).
     */
    static final Set<String> SCHEMES = Set.of("union", "jij", "path", "roimfs");

    /**
     * Jars a Forge release may not list: Forge 52 (Minecraft 1.21.1) has no roimfs and so no {@code roimfs} scheme; Forge
     * before 49 (Minecraft 1.20.1) has cpw's {@code securejarhandler}, the older name of {@code securemodules}, and Forge
     * from 49 on {@code securemodules} (exactly one of the two is required, {@link #SECURE_JARS}).
     */
    static final Set<String> OPTIONAL =
            Set.of("net.minecraftforge:roimfs", "net.minecraftforge:securemodules", "cpw.mods:securejarhandler");

    /** The two names of the jar that provides the {@code union} file system; a release lists one of them. */
    static final Set<String> SECURE_JARS = Set.of("net.minecraftforge:securemodules", "cpw.mods:securejarhandler");

    /** securemodules' file system provider: present when the system class loader sees the jars. */
    static final String UNION_PROVIDER_CLASS = "cpw/mods/niofs/union/UnionFileSystemProvider.class";

    private static final String JARS = "net.minecraftforge:securemodules, unsafe, JarJarFileSystems and roimfs";

    private final ClassLoader systemClassLoader;
    private final Optional<SystemClassPath> systemClassPath;
    private final Supplier<Set<String>> installedSchemes;

    /**
     * @param systemClassLoader the loader Java finds file systems with
     * @param systemClassPath appends to that loader, when Plexus Mod Loader was started with its agent
     * @param installedSchemes the schemes of the file systems Java lists; asked only after appending
     */
    ForgeFileSystems(
            ClassLoader systemClassLoader,
            Optional<SystemClassPath> systemClassPath,
            Supplier<Set<String>> installedSchemes) {
        this.systemClassLoader = systemClassLoader;
        this.systemClassPath = systemClassPath;
        this.installedSchemes = installedSchemes;
    }

    /** For this JVM: the system class loader and the file systems Java lists. */
    static ForgeFileSystems of(Optional<SystemClassPath> systemClassPath) {
        return new ForgeFileSystems(
                ClassLoader.getSystemClassLoader(), systemClassPath, ForgeFileSystems::installedSchemes);
    }

    /**
     * Checks, before anything is downloaded, that Forge's file systems are or can be put on the class path. Does not
     * ask Java for its file systems.
     *
     * @throws IOException when the class path does not have them and Plexus Mod Loader was started without its agent
     */
    void checkAvailable() throws IOException {
        if (onSystemClassPath() || systemClassPath.isPresent()) {
            return;
        }
        throw new IOException("Plexus Mod Loader was started without its Java agent, so it cannot put Forge's file"
                + " systems (" + JARS + ") on the JVM class path, the only place Java looks for file systems. For the"
                + " Minecraft Launcher, install the profile again with this version's installer; for a server, start it"
                + " with java -jar plexusmodloader-server.jar (run.bat or run.sh)");
    }

    /**
     * Appends {@code jars} (Forge's four, from {@link ForgeInstallation.Prepared#fileSystemJars()}) to the class path
     * when it does not have them yet, then checks that Java lists their file systems.
     *
     * @param log receives a line when the jars were appended
     * @throws IOException when the jars cannot be appended, appending had no effect, or Java does not list the file
     *     systems (because it listed them before the jars were appended)
     */
    void install(List<Path> jars, Consumer<String> log) throws IOException {
        boolean appended = false;
        if (!onSystemClassPath()) {
            SystemClassPath classPath = systemClassPath.orElseThrow(() -> new IOException(
                    "Forge's file systems are not on the JVM class path and Plexus Mod Loader cannot append them"));
            for (Path jar : jars) {
                classPath.append(jar);
            }
            if (!onSystemClassPath()) {
                throw new IOException("Plexus Mod Loader appended " + JARS + " to the JVM class path, but the system"
                        + " class loader still does not see " + UNION_PROVIDER_CLASS);
            }
            appended = true;
            log.accept("Added Forge's file systems to the JVM class path: "
                    + jars.stream().map(jar -> jar.getFileName().toString()).collect(Collectors.joining(", ")));
        }
        Set<String> installed = installedSchemes.get();
        Set<String> missing = new LinkedHashSet<>();
        boolean hasRoimfs =
                jars.stream().anyMatch(jar -> jar.getFileName().toString().startsWith("roimfs-"));
        for (String scheme : SCHEMES.stream().sorted().toList()) {
            if (scheme.equals("roimfs") && !hasRoimfs) {
                continue;
            }
            if (!installed.contains(scheme)) {
                missing.add(scheme);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        if (appended) {
            throw new IOException("the JVM listed its file systems before Plexus Mod Loader added " + JARS
                    + " to its class path, so Java does not know the file systems " + missing
                    + " that FML reads mod files with; something asked for the file systems before Forge was set up");
        }
        throw new IOException("Forge's file systems are on the JVM class path, but Java does not list " + missing);
    }

    /** Whether the system class loader sees securemodules' file system provider. */
    private boolean onSystemClassPath() {
        return systemClassLoader.getResource(UNION_PROVIDER_CLASS) != null;
    }

    private static Set<String> installedSchemes() {
        return FileSystemProvider.installedProviders().stream()
                .map(provider -> provider.getScheme().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
