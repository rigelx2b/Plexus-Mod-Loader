package dev.plexusmodloader.runtime.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Finds mod jars and classifies them by loader. Only the jars' metadata files are read; no class is
 * loaded during discovery.
 */
public final class ModFileScanner {

    private static final String MODLAUNCHER_SERVICE =
            "META-INF/services/cpw.mods.modlauncher.api.ITransformationService";

    /**
     * Scans {@code modsDirectory} (non-recursive, {@code *.jar} only, sorted by file name for a
     * deterministic order) and then {@code extraJars}.
     */
    public List<DiscoveredModFile> scan(Path modsDirectory, List<Path> extraJars) {
        List<Path> jars = new ArrayList<>();
        if (Files.isDirectory(modsDirectory)) {
            try (Stream<Path> files = Files.list(modsDirectory)) {
                files.filter(file -> file.getFileName().toString().endsWith(".jar"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .forEach(jars::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to list mods directory " + modsDirectory, e);
            }
        }
        jars.addAll(extraJars);
        return jars.stream().map(ModFileScanner::classify).toList();
    }

    /** Which of Forge and NeoForge a {@code META-INF/mods.toml} that has no {@code neoforge.mods.toml} beside it is for. */
    enum ModsTomlFamily {
        /**
         * No dependency on the {@code neoforge} mod (or the file cannot be read), and classes that are not NeoForge's
         * alone: a MinecraftForge mod, as before.
         */
        FORGE_ONLY,
        /**
         * Depends on the {@code neoforge} mod and not on {@code forge}, or its classes name only NeoForge's packages: a
         * NeoForge mod of FML 2 (NeoForge 20.4 and before).
         */
        NEOFORGE_ONLY,
        /** Depends on both: built for both loaders; it runs on whichever of the two the launch runs. */
        BOTH
    }

    private static ModsTomlFamily modsTomlFamily(JarFile jarFile) {
        ZipEntry entry = jarFile.getEntry(ModFileKind.FORGE.metadataPath());
        ModsTomlFamily family;
        try (InputStream input = jarFile.getInputStream(entry)) {
            family = modsTomlFamily(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return ModsTomlFamily.FORGE_ONLY;
        }
        // Many NeoForge 20.4 mods depend on neither neoforge nor forge (Entity Texture Features, BadOptimizations,
        // ImmediatelyFast, Mouse Tweaks, Dynamic FPS): their classes say which loader they are for.
        if (family == ModsTomlFamily.FORGE_ONLY && namesOnlyNeoForge(jarFile)) {
            return ModsTomlFamily.NEOFORGE_ONLY;
        }
        return family;
    }

    private static final byte[] NEOFORGE_PACKAGE = "net/neoforged/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FORGE_PACKAGE = "net/minecraftforge/".getBytes(StandardCharsets.US_ASCII);

    /**
     * Whether the jar's classes name NeoForge's packages ({@code net/neoforged/}) and never MinecraftForge's ({@code
     * net/minecraftforge/}): such a mod cannot run on MinecraftForge. Stops at the first class that names Forge's, so a
     * Forge mod's jar is seldom read to the end. Nested jars are not read.
     */
    static boolean namesOnlyNeoForge(JarFile jarFile) {
        boolean neoForge = false;
        var entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                continue;
            }
            byte[] bytes;
            try (InputStream input = jarFile.getInputStream(entry)) {
                bytes = input.readAllBytes();
            } catch (IOException e) {
                return false;
            }
            if (contains(bytes, FORGE_PACKAGE)) {
                return false;
            }
            neoForge |= contains(bytes, NEOFORGE_PACKAGE);
        }
        return neoForge;
    }

    private static boolean contains(byte[] bytes, byte[] part) {
        outer:
        for (int i = 0; i + part.length <= bytes.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (bytes[i + j] != part[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * The family of the mods.toml {@code toml}, from the {@code modId} of its dependency tables ({@code
     * [[dependencies.<mod>]]}); the {@code modId} of {@code [[mods]]} is the mod's own and does not count. Only the
     * table headers and {@code modId} lines are read, not the whole file.
     */
    static ModsTomlFamily modsTomlFamily(String toml) {
        boolean forge = false;
        boolean neoForge = false;
        boolean inDependencies = false;
        for (String raw : toml.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                String header = line.replace("[", "").replace("]", "").strip();
                inDependencies = header.equals("dependencies") || header.startsWith("dependencies.");
                continue;
            }
            if (!inDependencies || !line.startsWith("modId")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String value = line.substring(equals + 1).strip();
            int comment = value.indexOf('#');
            if (comment >= 0) {
                value = value.substring(0, comment).strip();
            }
            if (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            forge |= value.equals("forge");
            neoForge |= value.equals("neoforge");
        }
        if (neoForge) {
            return forge ? ModsTomlFamily.BOTH : ModsTomlFamily.NEOFORGE_ONLY;
        }
        return ModsTomlFamily.FORGE_ONLY;
    }

    /**
     * Classifies one jar. Never throws for the jar's contents: a file that is not a readable jar is {@link
     * ModFileKind#UNREADABLE}, so one damaged download cannot stop the others from being reported.
     */
    static DiscoveredModFile classify(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            ModFileKind found = null;
            Optional<String> nativeMetadata = Optional.empty();
            Set<ModFileKind> alsoFor = EnumSet.noneOf(ModFileKind.class);
            for (ModFileKind kind : ModFileKind.values()) {
                if (kind.metadataPath().isEmpty()) {
                    continue;
                }
                ZipEntry entry = jarFile.getEntry(kind.metadataPath());
                if (entry == null) {
                    continue;
                }
                if (found != null) {
                    alsoFor.add(kind);
                    continue;
                }
                found = kind;
                if (kind == ModFileKind.PLEXUS_NATIVE) {
                    try (InputStream input = jarFile.getInputStream(entry)) {
                        nativeMetadata = Optional.of(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            // A ModLauncher transformation service is Forge's too: Forge finds it in mods/ (CustomSkinLoader's jar for
            // every loader has fabric.mod.json and such a service, but no mods.toml).
            if (found != null && found != ModFileKind.FORGE && jarFile.getEntry(MODLAUNCHER_SERVICE) != null) {
                alsoFor.add(ModFileKind.FORGE);
            }
            // NeoForge 20.4's FML 2 (and 20.2, 20.3) reads META-INF/mods.toml, the file MinecraftForge mods have, and
            // knows no neoforge.mods.toml: a mods.toml alone says which of the two it is for by the mods it depends
            // on (ADR-0043).
            // A jar with both files whose mods.toml depends on neoforge alone (tr7zw's 1.20.4 builds) is a NeoForge mod
            // too: its mods.toml is for NeoForge 20.4, its neoforge.mods.toml for later NeoForge.
            if (found != null && jarFile.getEntry(ModFileKind.FORGE.metadataPath()) != null) {
                boolean neoForgeMetadata = jarFile.getEntry(ModFileKind.NEOFORGE.metadataPath()) != null;
                switch (modsTomlFamily(jarFile)) {
                    case NEOFORGE_ONLY -> {
                        // A mod for NeoForge, whatever else the jar has: the kind NeoForge's own metadata would give.
                        if (found == ModFileKind.FORGE) {
                            found = ModFileKind.NEOFORGE;
                        } else {
                            alsoFor.add(ModFileKind.NEOFORGE);
                        }
                        alsoFor.remove(ModFileKind.FORGE);
                        alsoFor.remove(found);
                    }
                    case BOTH -> {
                        if (!neoForgeMetadata) {
                            alsoFor.add(ModFileKind.NEOFORGE); // runs on either; the runtime that runs decides
                        }
                    }
                    case FORGE_ONLY -> {}
                }
            }
            if (found != null) {
                return new DiscoveredModFile(jar, found, nativeMetadata, Optional.empty(), alsoFor);
            }
            Manifest manifest = jarFile.getManifest();
            String fmlType =
                    manifest == null ? null : manifest.getMainAttributes().getValue("FMLModType");
            if (fmlType != null) {
                return new DiscoveredModFile(jar, ModFileKind.FML_LIBRARY, Optional.empty(), Optional.of(fmlType));
            }
            return new DiscoveredModFile(jar, ModFileKind.UNKNOWN, Optional.empty());
        } catch (IOException | RuntimeException e) {
            return new DiscoveredModFile(jar, ModFileKind.UNREADABLE, Optional.empty(), Optional.of(String.valueOf(e)));
        }
    }
}
