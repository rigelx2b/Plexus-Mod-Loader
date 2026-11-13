package dev.plexusmodloader.runtime.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.compat.bridge.BridgeLaunchContext;
import dev.plexusmodloader.game.version.layer.ModResourcePack;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ForeignModPacksTest {

    private static final Path JAR = Path.of("mods", "journeymap.jar");

    /** Forgified Fabric API next to NeoForge 21.1: its fabric_resource_loader_v0 provides fabric-resource-loader-v0. */
    private static final Map<String, String> FORGIFIED =
            Map.of("fabric_resource_loader_v0", "1.3.1+4ea8954419", "fabric-resource-loader-v0", "1.3.1+4ea8954419");

    private static BridgeLaunchContext.LoadedMod mod(String loader, String id, boolean runByHost, String... provided) {
        return new BridgeLaunchContext.LoadedMod(
                loader, id, "1.0.0", id + " name", List.of(provided), JAR, null, null, runByHost);
    }

    @Test
    void aHostBuildOfFabricsResourceLoaderGetsPlexusPacksForTheBridgedFabricAndQuiltMods() {
        List<ModResourcePack> packs = ForeignModPacks.needed(
                FORGIFIED,
                List.of(
                        mod("Plexus Mod Loader", "native_showcase", false),
                        mod("Fabric", "journeymap", false),
                        mod("NeoForge", "jade", true),
                        mod("Quilt", "flickview", false)));

        assertEquals(
                List.of(
                        new ModResourcePack("journeymap", "journeymap name", "1.0.0", JAR),
                        new ModResourcePack("flickview", "flickview name", "1.0.0", JAR)),
                packs);
    }

    @Test
    void withoutAHostBuildOfFabricsResourceLoaderFabricApiMakesThePacks() {
        assertTrue(ForeignModPacks.needed(Map.of(), List.of(mod("Fabric", "journeymap", false)))
                .isEmpty());
        assertTrue(ForeignModPacks.needed(Map.of("neoforge", "21.1.265"), List.of(mod("Fabric", "journeymap", false)))
                .isEmpty());
    }

    @Test
    void aBridgedResourceLoaderStillRunningMakesThePacksItself() {
        assertTrue(ForeignModPacks.needed(
                        FORGIFIED,
                        List.of(mod("Fabric", "journeymap", false), mod("Quilt", "quilt_resource_loader", false)))
                .isEmpty());
        assertTrue(ForeignModPacks.needed(
                        FORGIFIED,
                        List.of(
                                mod("Fabric", "journeymap", false),
                                mod("Fabric", "fabric-api", false, "fabric-resource-loader-v0")))
                .isEmpty());
    }
}
