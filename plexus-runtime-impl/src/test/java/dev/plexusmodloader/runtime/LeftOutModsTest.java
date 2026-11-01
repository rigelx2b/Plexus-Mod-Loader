package dev.plexusmodloader.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.plexusmodloader.compat.bridge.BridgeAssessment;
import dev.plexusmodloader.compat.bridge.CompatibilityFinding;
import dev.plexusmodloader.compat.bridge.CompatibilityStatus;
import dev.plexusmodloader.runtime.diagnostics.StartupReport;
import dev.plexusmodloader.runtime.discovery.ForeignModResolver;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which mods in the mods folder the loading screens and the title screen say are not loaded. */
class LeftOutModsTest {

    @Test
    void theHostsLeftOutModsAndUnloadableFilesAreNamed() {
        // A NeoForge mod without its required library (217), as in a player's launch with sophisticatedbackpacks.
        List<String> leftOut = PlexusRuntime.leftOutMods(
                List.of(new StartupReport.BlockedFile(
                        "broken.jar", "UNREADABLE", "Unreadable", "PLEXUS-DISCOVERY-003")),
                List.of(),
                List.of(),
                Set.of(),
                List.of("sophisticatedbackpacks"),
                Set.of("sodium", "iris"));

        assertEquals(List.of("broken.jar", "sophisticatedbackpacks"), leftOut);
    }

    @Test
    void anUnloadableForeignModInTheModsFolderIsNamed() {
        ForeignModResolver.ForeignMod missingDependency = mod("needs_api", null, "PLEXUS-COMPAT-111");
        ForeignModResolver.ForeignMod loaded = mod("sodium", null);

        assertEquals(
                List.of("needs_api"),
                PlexusRuntime.leftOutMods(
                        List.of(),
                        List.of(missingDependency, loaded),
                        List.of(loaded),
                        Set.of(missingDependency.jar(), loaded.jar()),
                        List.of(),
                        Set.of("sodium")));
    }

    /** Those have their own line (224), run in another form (122, 139, same ID), are nested, or are for the server. */
    @Test
    void modsThatAreNotMissingAreNotNamed() {
        List<ForeignModResolver.ForeignMod> mods = List.of(
                mod("host_incompatible", null, "PLEXUS-COMPAT-224"),
                mod("server_only", null, "PLEXUS-COMPAT-108"),
                mod("another_copy", null, "PLEXUS-COMPAT-122"),
                mod("nested_blocked", Path.of("sodium.jar"), "PLEXUS-COMPAT-111"),
                mod("dual_loader", null, "PLEXUS-COMPAT-111"));

        List<String> leftOut = PlexusRuntime.leftOutMods(
                List.of(
                        new StartupReport.BlockedFile("forgified.jar", "FABRIC", "Blocked", "PLEXUS-COMPAT-139"),
                        new StartupReport.BlockedFile(
                                "sodium.jar!/META-INF/jars/lib.jar", "UNKNOWN", "NotAMod", "PLEXUS-DISCOVERY-002")),
                mods,
                List.of(),
                mods.stream()
                        .filter(mod -> mod.containerJar() == null)
                        .map(ForeignModResolver.ForeignMod::jar)
                        .collect(java.util.stream.Collectors.toSet()),
                List.of("dual_loader"),
                Set.of("dual_loader"));

        assertEquals(List.of(), leftOut);
    }

    private static ForeignModResolver.ForeignMod mod(String id, Path container, String... blockingCodes) {
        List<CompatibilityFinding> findings = Arrays.stream(blockingCodes)
                .map(code -> new CompatibilityFinding(code, true, "test", "test"))
                .toList();
        BridgeAssessment assessment =
                BridgeAssessment.of("Fabric", id, "1.0.0", findings, List.of(), CompatibilityStatus.EXPERIMENTAL);
        return new ForeignModResolver.ForeignMod(
                Path.of(id + ".jar"), null, assessment, container, container == null ? null : "META-INF/jars/x.jar");
    }
}
