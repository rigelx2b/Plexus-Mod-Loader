package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.compat.bridge.GameHost;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Forge on NeoForge (ADR-0051, F-N2): the jars of Forge's own API that join NeoForge's loader libraries, so that NeoForge's
 * FML constructs a Forge mod with Forge's {@code FMLModContainer} and the mod's Forge classes are Forge's. They come from
 * the Forge release Plexus Mod Loader pins for the game version, downloaded and checked as for a Forge launch ({@link
 * ForgeInstallation#runtimeLibraries}); no Forge code is copied or rewritten. Their packages do not overlap NeoForge's
 * (checked against NeoForge 26.2.0.93's libraries, {@code docs/plans/FORGE_ON_NEOFORGE.md}).
 *
 * <p>Thread: one launch prepares at a time, as {@link ForgeInstallation}.
 */
final class ForgeOnNeoForgeLibraries {

    /**
     * File name prefixes of the jars taken: Forge's event bus 7, its SPI, FML's core and Java language provider, the
     * secure jar handling and unsafe access {@code FMLModContainer} uses, Forge's loader jar for its paths ({@code
     * FMLPaths}, which the host sets to the game's; Forge's {@code FMLLoader} in it is never started, the host only sets
     * its side and game directory, {@link ForgeEnvironmentCheck}), Forge's {@code mergetool-api} for its {@code Dist},
     * and Forge's ModLauncher, whose API types {@code FMLLoader}'s code names: without them the JVM cannot link {@code
     * FMLLoader} (its package {@code cpw.mods.modlauncher} is not among NeoForge 26.x's libraries).
     */
    static final List<String> PREFIXES = List.of(
            "eventbus-",
            "forgespi-",
            "fmlcore-",
            "javafmllanguage-",
            "securemodules-",
            "unsafe-",
            "fmlloader-",
            "mergetool-api-",
            "modlauncher-");

    private ForgeOnNeoForgeLibraries() {}

    /**
     * What a NeoForge host runs of the Forge mods the runtime handed over ({@link GameHost.Request#forgeModJars()}).
     *
     * @param libraries Forge's API jars for the loader library layer; empty when no Forge mod runs
     * @param kept the Forge mods FML loads
     * @param declined the Forge mods left out, each logged with why ({@code PLEXUS-COMPAT-229}); the runtime reports them
     * @param forgeVersion the Forge release whose API the mods get, without the Minecraft version ({@code 65.1.5}); empty
     *     when Forge's jars were not prepared
     */
    record Mods(List<Path> libraries, List<Path> kept, List<Path> declined, String forgeVersion) {
        Mods {
            libraries = List.copyOf(libraries);
            kept = List.copyOf(kept);
            declined = List.copyOf(declined);
        }
    }

    /**
     * The Forge mods FML can load ({@code kept}) and those declined, each with its reasons, in the order given.
     *
     * @param declined by jar: the reasons it is left out
     */
    record Checked(List<Path> kept, Map<Path, List<String>> declined) {
        Checked {
            kept = List.copyOf(kept);
            declined = Collections.unmodifiableMap(new LinkedHashMap<>(declined));
        }
    }

    /**
     * Prepares the Forge mods of {@code request} for a NeoForge host: Forge's API jars, and each mod checked against its
     * {@code mods.toml} as MinecraftForge's FML would ({@link ForgeModRequirements}, with the Forge release Plexus Mod
     * Loader pins and the Maven version ranges of {@code mavenArtifact}): its Minecraft and other mods, its language
     * loader and version, and ({@link #languageReasons}) {@code javafml} with an {@code @Mod} class for each of its mods.
     * A mod that fails, or every mod when Forge's jars cannot be prepared or a pair of {@link ForgeEventPairs} does not
     * hold for NeoForge's {@code neoForgeUniversal} and Forge's universal jar, or registration does not ({@link
     * ForgeEventPairCheck}), or Forge's side and game directory cannot be set ({@link ForgeEnvironmentCheck}), or
     * Forge's {@code ModList} cannot be set up ({@link ForgeModListCheck}), is
     * declined rather than stopping NeoForge, so that the NeoForge mods still run.
     */
    static Mods mods(GameHost.Request request, Path mavenArtifact, Path neoForgeUniversal) {
        List<Path> jars = request.forgeModJars();
        if (jars.isEmpty()) {
            return new Mods(List.of(), List.of(), List.of(), "");
        }
        List<Path> libraries;
        String forgeVersion;
        try {
            ForgeInstallation.Release release = ForgeInstallation.release(request.gameVersion())
                    .orElseThrow(() -> new IOException(
                            "Plexus Mod Loader has no MinecraftForge release to take Forge's API from for Minecraft "
                                    + request.gameVersion()));
            libraries = select(
                    ForgeInstallation.runtimeLibraries(
                            release,
                            request.cacheDirectory().resolve("forge"),
                            ForgeInstallation.httpDownloader(),
                            request.log()),
                    release.forgeVersion());
            forgeVersion =
                    release.forgeVersion().substring(release.forgeVersion().indexOf('-') + 1);
            List<String> unpaired =
                    ForgeEventPairCheck.missing(ForgeEventPairs.GAME_EVENTS, neoForgeUniversal, libraries.getLast());
            if (!unpaired.isEmpty()) {
                return declineAll(request, jars, "game event pairs do not hold: " + String.join("; ", unpaired));
            }
            List<String> unreloaded =
                    ForgeEventPairCheck.missingReloadListeners(neoForgeUniversal, libraries.getLast());
            if (!unreloaded.isEmpty()) {
                return declineAll(
                        request,
                        jars,
                        "reload listener event forwarding hooks missing: " + String.join("; ", unreloaded));
            }
            List<String> unmapped = ForgeEventPairCheck.missingKeyMappings(neoForgeUniversal, libraries.getLast());
            if (!unmapped.isEmpty()) {
                return declineAll(
                        request, jars, "key mapping event forwarding hooks missing: " + String.join("; ", unmapped));
            }
            List<String> unregistered = ForgeEventPairCheck.missingRegistration(neoForgeUniversal, libraries.getLast());
            if (!unregistered.isEmpty()) {
                return declineAll(
                        request, jars, "registry event forwarding hooks missing: " + String.join("; ", unregistered));
            }
            int busCalls = ForgeEventBusPatch.calls(
                    only(libraries, name -> name.startsWith("javafmllanguage-"), "javafmllanguage-*.jar"));
            if (busCalls != 1) {
                return declineAll(
                        request,
                        jars,
                        "event subscriber bus hook missing: " + ForgeEventBusPatch.CLASS_FILE + " has " + busCalls
                                + " Bindings.getForgeBus() call(s)");
            }
            List<String> unconfigurable = new ArrayList<>();
            ForgeConfigPatch.calls(only(libraries, name -> name.startsWith("fmlcore-"), "fmlcore-*.jar"))
                    .forEach((redirect, calls) -> {
                        if (calls != 1) {
                            unconfigurable.add(redirect.classFile() + " has " + calls + " " + redirect.owner() + "."
                                    + redirect.name() + " call(s)");
                        }
                    });
            if (!unconfigurable.isEmpty()) {
                return declineAll(request, jars, "config hooks missing: " + String.join("; ", unconfigurable));
            }
            List<String> unnetworked = ForgeNetworkRules.missing(libraries.getLast());
            if (!unnetworked.isEmpty()) {
                return declineAll(request, jars, "network hooks missing: " + String.join("; ", unnetworked));
            }
            List<String> unsettable = ForgeEnvironmentCheck.missing(
                    only(libraries, name -> name.startsWith("fmlloader-"), "fmlloader-*.jar"),
                    only(libraries, name -> name.startsWith("mergetool-api-"), "mergetool-api-*.jar"));
            if (!unsettable.isEmpty()) {
                return declineAll(
                        request,
                        jars,
                        "Forge's side and game directory cannot be set: " + String.join("; ", unsettable));
            }
            List<String> noModList = ForgeModListCheck.missing(
                    only(libraries, name -> name.startsWith("fmlcore-"), "fmlcore-*.jar"),
                    only(libraries, name -> name.startsWith("fmlloader-"), "fmlloader-*.jar"));
            int modInfoReads =
                    ForgeModInfosPatch.reads(only(libraries, name -> name.startsWith("fmlcore-"), "fmlcore-*.jar"));
            if (modInfoReads != 1) {
                noModList = new ArrayList<>(noModList);
                noModList.add(ForgeModInfosPatch.OWNER + "." + ForgeModInfosPatch.METHOD + "() has " + modInfoReads
                        + " read(s) of " + ForgeModInfosPatch.FIELD);
            }
            if (!noModList.isEmpty()) {
                return declineAll(request, jars, "Forge's ModList cannot be set up: " + String.join("; ", noModList));
            }
        } catch (IOException e) {
            return declineAll(request, jars, e.toString());
        }
        Checked checked;
        try {
            checked = check(
                    request.gameVersion(),
                    request.side(),
                    request.log(),
                    mavenArtifact,
                    forgeVersion,
                    jars,
                    ForgePackMetadataCheck.stopsOnRejectedMetadata(neoForgeUniversal));
        } catch (IOException | ReflectiveOperationException e) {
            return declineAll(request, jars, e.toString());
        }
        return new Mods(
                checked.kept().isEmpty() ? List.of() : libraries,
                checked.kept(),
                List.copyOf(checked.declined().keySet()),
                forgeVersion);
    }

    /**
     * Checks {@code jars} as {@link #mods} does ({@link ForgeModRequirements} with Forge {@code forgeVersion}, then
     * {@link #languageReasons}), again on what is left until nothing more is declined: a mod declined for its language
     * loader leaves out the mods that require it, as a missing mod would stop FML's whole game. Each declined mod is
     * logged ({@code PLEXUS-COMPAT-229}).
     *
     * @throws IOException when {@code mavenArtifact} cannot be read
     * @throws ReflectiveOperationException when its Maven version ranges are not the API checked
     */
    static Checked check(
            String gameVersion,
            GameSide side,
            Consumer<String> log,
            Path mavenArtifact,
            String forgeVersion,
            List<Path> jars)
            throws IOException, ReflectiveOperationException {
        return check(gameVersion, side, log, mavenArtifact, forgeVersion, jars, false);
    }

    /**
     * As {@link #check(String, GameSide, Consumer, Path, String, List)}; with {@code strictPackMetadata} (a NeoForge
     * release that reads mod pack metadata without a fallback, {@link ForgePackMetadataCheck#stopsOnRejectedMetadata}),
     * a mod whose {@code pack.mcmeta} would stop it is declined too ({@link ForgePackMetadataCheck#reasons}).
     */
    static Checked check(
            String gameVersion,
            GameSide side,
            Consumer<String> log,
            Path mavenArtifact,
            String forgeVersion,
            List<Path> jars,
            boolean strictPackMetadata)
            throws IOException, ReflectiveOperationException {
        List<Path> candidates = new ArrayList<>(jars);
        Map<Path, List<String>> declined = new LinkedHashMap<>();
        try (URLClassLoader maven = new URLClassLoader(
                new URL[] {mavenArtifact.toUri().toURL()}, ForgeOnNeoForgeLibraries.class.getClassLoader())) {
            ForgeModRequirements.VersionRanges ranges = ForgeModRequirements.maven(maven);
            boolean changed = true;
            while (changed) {
                changed = false;
                ForgeModRequirements.Result result =
                        ForgeModRequirements.check(candidates, gameVersion, forgeVersion, side, ranges);
                for (ForgeModRequirements.Excluded excluded : result.excluded()) {
                    candidates.remove(excluded.jar());
                    declined.put(excluded.jar(), excluded.reasons());
                    changed = true;
                    log.accept("PLEXUS-COMPAT-229 Forge mod " + excluded.modId() + " ("
                            + excluded.jar().getFileName() + ") is NOT loaded: "
                            + String.join("; ", excluded.reasons()));
                }
                for (Path jar : result.kept()) {
                    List<String> reasons = new ArrayList<>(languageReasons(jar));
                    if (strictPackMetadata) {
                        reasons.addAll(ForgePackMetadataCheck.reasons(jar));
                    }
                    if (!reasons.isEmpty()) {
                        candidates.remove(jar);
                        declined.put(jar, reasons);
                        changed = true;
                        log.accept("PLEXUS-COMPAT-229 " + jar.getFileName() + " is NOT loaded: "
                                + String.join("; ", reasons));
                    }
                }
            }
        }
        for (Path jar : candidates) {
            if (lowCode(jar)) {
                log.accept(
                        "Forge on NeoForge: " + jar.getFileName() + " is a data-only mod (modLoader " + LOW_CODE
                                + "); NeoForge's FML loads it with its own mod container, for its resources and data (ADR-0051 23)");
            }
        }
        return new Checked(candidates, declined);
    }

    private static Mods declineAll(GameHost.Request request, List<Path> jars, String reason) {
        for (Path jar : jars) {
            request.log().accept("PLEXUS-COMPAT-229 " + jar.getFileName() + " is NOT loaded: " + reason);
        }
        return new Mods(List.of(), List.of(), jars, "");
    }

    /** Forge's language loader of data-only mods (data packs and resource packs packed as mods). */
    static final String LOW_CODE = "lowcodefml";

    /**
     * Whether {@code jar} is a data-only mod of Forge's {@link #LOW_CODE} loader. It has no class to construct, so the
     * host gives it no language loader and NeoForge's FML loads it with its default one, as a NeoForge mod without a mod
     * class (ADR-0051 23): its resources and data are the game's, and it is not in Forge's {@code ModList}.
     */
    static boolean lowCode(Path jar) {
        try {
            ForgeModRequirements.ModsToml toml = ForgeModRequirements.ModsToml.read(jar);
            return toml != null && LOW_CODE.equals(toml.modLoader());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * What keeps Plexus Mod Loader's Forge language loader from constructing {@code jar}'s mods: a {@code modLoader}
     * other than {@code javafml} (such as another language provider's), or the {@code javafml} mod IDs without an
     * {@code @Mod} class (a data-only jar), with the words of {@link ForgeModRequirements#NO_MOD_CLASS}. A {@link
     * #LOW_CODE} mod has none: it runs without a language loader of the host ({@link #lowCode}).
     */
    static List<String> languageReasons(Path jar) {
        try {
            ForgeModRequirements.ModsToml toml = ForgeModRequirements.ModsToml.read(jar);
            if (toml == null) {
                return List.of(NeoForgeModRequirements.LEGACY_METADATA);
            }
            if (LOW_CODE.equals(toml.modLoader())) {
                return List.of();
            }
            if (!"javafml".equals(toml.modLoader())) {
                return List.of("modLoader " + toml.modLoader());
            }
            List<String> missing = ForgeModRequirements.modIdsWithoutModClass(
                    jar, toml.versions().keySet());
            if (!missing.isEmpty()) {
                return List.of(ForgeModRequirements.NO_MOD_CLASS + String.join(", ", missing));
            }
            return List.of();
        } catch (IOException | RuntimeException e) {
            return List.of(e.toString());
        }
    }

    /** One jar for each of {@link #PREFIXES}, and Forge's {@code universal} jar (its lifecycle events). */
    static List<Path> select(List<Path> libraries, String forgeVersion) throws IOException {
        List<Path> selected = new ArrayList<>();
        for (String prefix : PREFIXES) {
            selected.add(only(libraries, name -> name.startsWith(prefix), prefix + "*.jar"));
        }
        String universal = "forge-" + forgeVersion + "-universal.jar";
        selected.add(only(libraries, name -> name.equals(universal), universal));
        return List.copyOf(selected);
    }

    private static Path only(List<Path> libraries, Predicate<String> name, String what) throws IOException {
        List<Path> found = libraries.stream()
                .filter(jar -> name.test(jar.getFileName().toString()))
                .toList();
        if (found.size() != 1) {
            throw new IOException(
                    "MinecraftForge's libraries do not have exactly one " + what + " (" + found.size() + ")");
        }
        return found.getFirst();
    }
}
