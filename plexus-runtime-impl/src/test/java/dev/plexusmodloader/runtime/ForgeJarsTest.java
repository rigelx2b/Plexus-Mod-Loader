package dev.plexusmodloader.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.plexusmodloader.runtime.discovery.DiscoveredModFile;
import dev.plexusmodloader.runtime.discovery.ModFileKind;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which jars MinecraftForge's FML runs, when some jars are built for several loaders. */
class ForgeJarsTest {

    private static final DiscoveredModFile FORGE = file("forge.jar", ModFileKind.FORGE);
    private static final DiscoveredModFile FABRIC = file("fabric.jar", ModFileKind.FABRIC);
    private static final DiscoveredModFile EVERY_LOADER =
            file("every-loader.jar", ModFileKind.FABRIC, ModFileKind.NEOFORGE, ModFileKind.FORGE);
    private static final DiscoveredModFile FORGE_AND_NEOFORGE =
            file("forge-and-neoforge.jar", ModFileKind.NEOFORGE, ModFileKind.FORGE);
    private static final DiscoveredModFile NEOFORGE = file("neoforge.jar", ModFileKind.NEOFORGE);
    private static final DiscoveredModFile PLEXUS = file("plexus.jar", ModFileKind.PLEXUS_NATIVE, ModFileKind.FORGE);

    @Test
    void withForgeModsAndNoModOnlyFabricRunsForgeRunsTheJarsForSeveralLoaders() {
        assertEquals(
                List.of(path(FORGE), path(EVERY_LOADER), path(FORGE_AND_NEOFORGE)),
                PlexusRuntime.forgeJars(List.of(FORGE, EVERY_LOADER, FORGE_AND_NEOFORGE, NEOFORGE, PLEXUS)));
    }

    /** With Fabric mods in the same game too (ADR-0021 G8), Forge still runs the jars for several loaders. */
    @Test
    void withAModOnlyFabricRunsForgeStillRunsTheJarsForSeveralLoaders() {
        assertEquals(
                List.of(path(FORGE), path(EVERY_LOADER)),
                PlexusRuntime.forgeJars(List.of(FORGE, FABRIC, EVERY_LOADER)));
    }

    @Test
    void withoutAModOnlyForgeRunsForgeDoesNotStart() {
        assertEquals(List.of(), PlexusRuntime.forgeJars(List.of(EVERY_LOADER, FORGE_AND_NEOFORGE, FABRIC)));
    }

    /** A mods.toml that depends on both Forge and NeoForge (ADR-0043): Forge kind, also for NeoForge. */
    private static final DiscoveredModFile EITHER = file("either.jar", ModFileKind.FORGE, ModFileKind.NEOFORGE);

    /** The jar for either loader runs on NeoForge when there are NeoForge mods: it does not make Forge the runtime. */
    @Test
    void aJarForEitherLoaderDoesNotMakeForgeTheRuntimeBesideNeoForgeMods() {
        assertEquals(List.of(), PlexusRuntime.forgeJars(List.of(EITHER, NEOFORGE)));
        assertEquals(List.of(path(EITHER), path(NEOFORGE)), PlexusRuntime.neoForgeJars(List.of(EITHER, NEOFORGE)));
    }

    /** With Forge mods, or with nothing else, Forge runs it, as it did before the jar was told apart. */
    @Test
    void aJarForEitherLoaderRunsOnForgeWithForgeModsOrAlone() {
        assertEquals(List.of(path(EITHER), path(FORGE)), PlexusRuntime.forgeJars(List.of(EITHER, FORGE)));
        assertEquals(List.of(path(EITHER)), PlexusRuntime.forgeJars(List.of(EITHER)));
        assertEquals(List.of(path(FORGE), path(EITHER)), PlexusRuntime.forgeJars(List.of(FORGE, EITHER, NEOFORGE)));
    }

    private static DiscoveredModFile file(String name, ModFileKind kind, ModFileKind... alsoFor) {
        return new DiscoveredModFile(Path.of(name), kind, Optional.empty(), Optional.empty(), Set.of(alsoFor));
    }

    private static Path path(DiscoveredModFile file) {
        return file.path();
    }
}
