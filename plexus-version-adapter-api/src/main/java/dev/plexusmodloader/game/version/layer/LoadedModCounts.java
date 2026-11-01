package dev.plexusmodloader.game.version.layer;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.util.List;
import java.util.Objects;

/**
 * How many mods each loader loaded in this launch, for the title screen's bottom-left text ({@link ModCountLines}) and
 * the loading screens ({@link LoadingScreenModLines}).
 *
 * <p>Counting rule (the runtime applies it): every mod a loader actually loads counts once by mod ID, whether the
 * player installed it or an installed mod bundled it (a nested jar); mods that are not loaded (blocked, missing a
 * dependency, wrong side) do not count. Loader runtimes and stand-in mods do not count: Plexus Mod Loader itself, {@code
 * minecraft}, {@code java}, {@code fabricloader}, {@code quilt_loader}, {@code forge} and {@code neoforge}. Of those,
 * {@link #installed} are the ones the player put in the mods folder; the rest came bundled inside them.
 *
 * @param plexusMods Plexus mods (Native API) Plexus Mod Loader loaded
 * @param fabricMods Fabric mods the Fabric bridge loaded
 * @param forgeMods MinecraftForge mods FML loads when Forge runs the game (ADR-0021)
 * @param quiltMods Quilt mods ({@code quilt.mod.json}) the Quilt bridge loaded (ADR-0027)
 * @param neoForgeMods NeoForge mods FML 11 loads when NeoForge runs the game (ADR-0026)
 * @param hostIncompatibleMods other loaders' mods that are not loaded because Plexus Mod Loader cannot run them as
 *     written on the game the host loader patched ({@code PLEXUS-COMPAT-224}, ADR-0030); not counted among the others
 * @param installed of each loader's mods, those loaded from a jar in the mods folder rather than from a jar nested in
 *     one; never more than the loader's count
 * @param leftOutMods the mods in the mods folder that are not loaded for another reason than {@code
 *     PLEXUS-COMPAT-224} (a missing or wrong dependency, a block, an unreadable file), by mod ID or, without one, file
 *     name; a mod the launch runs in another form (another copy, the host's build of it) or that is for the other side is
 *     not among them
 * @param hostIncompatibleModIds the IDs of the {@code hostIncompatibleMods}, when known; empty otherwise
 */
@ApiStability(StabilityLevel.INTERNAL)
public record LoadedModCounts(
        int plexusMods,
        int fabricMods,
        int forgeMods,
        int quiltMods,
        int neoForgeMods,
        int hostIncompatibleMods,
        Installed installed,
        List<String> leftOutMods,
        List<String> hostIncompatibleModIds) {

    /** No mods of any loader. */
    public static final LoadedModCounts NONE = new LoadedModCounts(0, 0, 0, 0, 0, 0);

    /**
     * Of each loader's loaded mods, how many came from a jar in the mods folder.
     *
     * @param plexusMods installed Plexus mods
     * @param fabricMods installed Fabric mods
     * @param forgeMods installed MinecraftForge mods
     * @param quiltMods installed Quilt mods
     * @param neoForgeMods installed NeoForge mods
     */
    public record Installed(int plexusMods, int fabricMods, int forgeMods, int quiltMods, int neoForgeMods) {

        public Installed {
            if (plexusMods < 0 || fabricMods < 0 || forgeMods < 0 || quiltMods < 0 || neoForgeMods < 0) {
                throw new IllegalArgumentException("installed mod counts cannot be negative: " + plexusMods + ", "
                        + fabricMods + ", " + forgeMods + ", " + quiltMods + ", " + neoForgeMods);
            }
        }
    }

    public LoadedModCounts {
        Objects.requireNonNull(installed, "installed");
        leftOutMods = List.copyOf(leftOutMods);
        hostIncompatibleModIds = List.copyOf(hostIncompatibleModIds);
        if (!hostIncompatibleModIds.isEmpty() && hostIncompatibleModIds.size() != hostIncompatibleMods) {
            throw new IllegalArgumentException("the IDs of " + hostIncompatibleMods + " mods left out for the host's"
                    + " patch are " + hostIncompatibleModIds);
        }
        if (plexusMods < 0
                || fabricMods < 0
                || forgeMods < 0
                || quiltMods < 0
                || neoForgeMods < 0
                || hostIncompatibleMods < 0) {
            throw new IllegalArgumentException("mod counts cannot be negative: " + plexusMods + ", " + fabricMods + ", "
                    + forgeMods + ", " + quiltMods + ", " + neoForgeMods + ", " + hostIncompatibleMods);
        }
        if (installed.plexusMods() > plexusMods
                || installed.fabricMods() > fabricMods
                || installed.forgeMods() > forgeMods
                || installed.quiltMods() > quiltMods
                || installed.neoForgeMods() > neoForgeMods) {
            throw new IllegalArgumentException("more installed mods than loaded ones: " + installed);
        }
    }

    /** Counts of loaded mods, all of them installed, none left out. */
    public LoadedModCounts(
            int plexusMods, int fabricMods, int forgeMods, int quiltMods, int neoForgeMods, int hostIncompatibleMods) {
        this(
                plexusMods,
                fabricMods,
                forgeMods,
                quiltMods,
                neoForgeMods,
                hostIncompatibleMods,
                new Installed(plexusMods, fabricMods, forgeMods, quiltMods, neoForgeMods),
                List.of(),
                List.of());
    }

    /** Counts of loaded mods, none left out for the host's patch. */
    public LoadedModCounts(int plexusMods, int fabricMods, int forgeMods, int quiltMods, int neoForgeMods) {
        this(plexusMods, fabricMods, forgeMods, quiltMods, neoForgeMods, 0);
    }

    /** Counts without NeoForge mods. */
    public LoadedModCounts(int plexusMods, int fabricMods, int forgeMods, int quiltMods) {
        this(plexusMods, fabricMods, forgeMods, quiltMods, 0);
    }

    /** Counts without Quilt and NeoForge mods. */
    public LoadedModCounts(int plexusMods, int fabricMods, int forgeMods) {
        this(plexusMods, fabricMods, forgeMods, 0, 0);
    }

    /** These counts with {@code count} mods left out for the host's patch ({@code PLEXUS-COMPAT-224}). */
    public LoadedModCounts withHostIncompatibleMods(int count) {
        return new LoadedModCounts(
                plexusMods, fabricMods, forgeMods, quiltMods, neoForgeMods, count, installed, leftOutMods, List.of());
    }

    /** These counts with the mods of {@code ids} left out for the host's patch ({@code PLEXUS-COMPAT-224}). */
    public LoadedModCounts withHostIncompatibleMods(List<String> ids) {
        return new LoadedModCounts(
                plexusMods, fabricMods, forgeMods, quiltMods, neoForgeMods, ids.size(), installed, leftOutMods, ids);
    }

    /** These counts with {@code mods} of them installed in the mods folder, the others bundled inside those. */
    public LoadedModCounts withInstalled(Installed mods) {
        return new LoadedModCounts(
                plexusMods,
                fabricMods,
                forgeMods,
                quiltMods,
                neoForgeMods,
                hostIncompatibleMods,
                mods,
                leftOutMods,
                hostIncompatibleModIds);
    }

    /** These counts with {@code mods} in the mods folder left out ({@link #leftOutMods}). */
    public LoadedModCounts withLeftOutMods(List<String> mods) {
        return new LoadedModCounts(
                plexusMods,
                fabricMods,
                forgeMods,
                quiltMods,
                neoForgeMods,
                hostIncompatibleMods,
                installed,
                mods,
                hostIncompatibleModIds);
    }
}
