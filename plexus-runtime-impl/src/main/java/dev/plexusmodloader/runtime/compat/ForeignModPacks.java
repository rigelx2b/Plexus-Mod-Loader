package dev.plexusmodloader.runtime.compat;

import dev.plexusmodloader.compat.bridge.BridgeLaunchContext;
import dev.plexusmodloader.game.version.layer.ModResourcePack;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The other loaders' mods whose jars Plexus Mod Loader exposes as resource and data packs itself, because the Fabric API
 * build that serves them does not.
 *
 * <p>Fabric API's resource loader ({@code fabric-resource-loader-v0}) makes a pack of every mod {@code FabricLoader}
 * lists ({@code ModResourcePackCreator.loadPacks} calls {@code registerModPack(consumer, null, BASE_PARENT)}), so when
 * Plexus Mod Loader runs Fabric API's own build, Fabric and Quilt mods get their packs from it as under Fabric Loader.
 * A host's build that stands in for it does not have to: Forgified Fabric API 0.116.15+2.3.6+1.21.1 next to NeoForge
 * 21.1 ({@code fabric-resource-loader-v0} 1.3.1+4ea8954419, checked with {@code javap}) registers only the {@code
 * fabric} placeholder pack (and the programmer art and high contrast packs on the client), because under Sinytra
 * Connector Fabric mods are NeoForge mod files whose packs NeoForge makes. Plexus Mod Loader keeps other loaders' mods
 * out of FML's mod files (ADR-0025), so without this no one reads their assets and data (ADR-0043).
 *
 * <p>Not when one of the mods Plexus Mod Loader runs is a resource loader itself (Fabric API's {@code
 * fabric-resource-loader-v0}, or QSL's {@code quilt_resource_loader}, which makes Quilt and Fabric mods' packs under
 * Quilt): it makes them, and a second pack of the same jar would be read twice.
 */
public final class ForeignModPacks {

    /** The ID of Fabric API's resource loader, as Fabric API's module and the host builds that stand in for it name it. */
    public static final String FABRIC_RESOURCE_LOADER = "fabric-resource-loader-v0";

    private static final Set<String> RESOURCE_LOADERS = Set.of(FABRIC_RESOURCE_LOADER, "quilt_resource_loader");

    private static final String PLEXUS = "Plexus Mod Loader";

    private ForeignModPacks() {}

    /**
     * The packs Plexus Mod Loader adds for other loaders' mods, in load order; empty unless the host's build stands in
     * for Fabric API's resource loader and none of {@code runMods} is a resource loader.
     *
     * @param hostStandIns the IDs the host's mods meet (their IDs and provided IDs), with the versions they meet them at
     * @param runMods the mods Plexus Mod Loader runs (Plexus Mods and the bridges' mods), in load order
     */
    public static List<ModResourcePack> needed(
            Map<String, String> hostStandIns, List<BridgeLaunchContext.LoadedMod> runMods) {
        if (!hostStandIns.containsKey(FABRIC_RESOURCE_LOADER)) {
            return List.of();
        }
        boolean bridgedResourceLoader = runMods.stream()
                .filter(mod -> !mod.runByHost())
                .anyMatch(mod -> RESOURCE_LOADERS.contains(mod.modId())
                        || mod.providedIds().stream().anyMatch(RESOURCE_LOADERS::contains));
        if (bridgedResourceLoader) {
            return List.of();
        }
        return runMods.stream()
                .filter(mod -> !mod.runByHost() && !mod.loader().equals(PLEXUS))
                .map(mod -> new ModResourcePack(mod.modId(), mod.name(), mod.version(), mod.jar()))
                .toList();
    }
}
