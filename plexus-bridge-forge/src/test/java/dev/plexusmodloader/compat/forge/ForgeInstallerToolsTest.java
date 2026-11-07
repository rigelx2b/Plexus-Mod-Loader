package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ADR-0038: Forge 1.21.1's and 1.21.11's {@code install_profile.json} renames the obfuscated jar with {@code
 * ForgeAutoRenamingTool} after {@code DOWNLOAD_MOJMAPS}; the versions from 26.1 on have neither. The profile below has
 * the shape of Forge 1.21.11-61.2.5's (checked against the real installer, 2026-11-05).
 */
class ForgeInstallerToolsTest {

    private static final String PROFILE = """
            {
              "processors": [
                {"jar": "net.minecraftforge:installertools:1.4.3", "sides": ["server"],
                 "classpath": ["net.sf.jopt-simple:jopt-simple:6.0-alpha-3"],
                 "args": ["--task", "BUNDLER_EXTRACT", "--input", "{MINECRAFT_JAR}"]},
                {"jar": "net.minecraftforge:installertools:1.4.3",
                 "classpath": ["net.sf.jopt-simple:jopt-simple:6.0-alpha-3", "com.google.code.gson:gson:2.10.1"],
                 "args": ["--task", "DOWNLOAD_MOJMAPS", "--sanitize", "--version", "1.21.11", "--side", "{SIDE}",
                          "--output", "{MOJMAPS}"]},
                {"jar": "net.minecraftforge:ForgeAutoRenamingTool:1.0.6", "sides": ["server"],
                 "classpath": ["net.sf.jopt-simple:jopt-simple:6.0-alpha-3"],
                 "args": ["--input", "{MC_UNPACKED}", "--output", "{MC_OFF}", "--names", "{MOJMAPS}", "--reverse"]},
                {"jar": "net.minecraftforge:ForgeAutoRenamingTool:1.0.6", "sides": ["client"],
                 "classpath": ["net.sf.jopt-simple:jopt-simple:6.0-alpha-3"],
                 "args": ["--input", "{MINECRAFT_JAR}", "--output", "{MC_OFF}", "--names", "{MOJMAPS}", "--reverse"]},
                {"jar": "net.minecraftforge:binarypatcher:1.3.1", "args": ["--clean", "{MC_OFF}"]}
              ]
            }
            """;

    @Test
    void theRenamingStepsOfASideAreFound() {
        Optional<ForgeInstallerTools.Plan> plan = ForgeInstallerTools.plan(profile(PROFILE), "client");

        assertTrue(plan.isPresent());
        assertEquals(2, plan.get().steps().size());
        assertEquals(
                List.of(
                        "net.minecraftforge:installertools:1.4.3",
                        "net.sf.jopt-simple:jopt-simple:6.0-alpha-3",
                        "com.google.code.gson:gson:2.10.1"),
                plan.get().steps().getFirst().classPath());
        assertEquals(
                List.of("net.minecraftforge:ForgeAutoRenamingTool:1.0.6", "net.sf.jopt-simple:jopt-simple:6.0-alpha-3"),
                plan.get().rename().classPath());
        assertEquals("{MINECRAFT_JAR}", plan.get().rename().arguments().get(1));
    }

    @Test
    void theServerRenamesTheUnpackedServerJar() {
        ForgeInstallerTools.Plan plan =
                ForgeInstallerTools.plan(profile(PROFILE), "server").orElseThrow();

        assertEquals("{MC_UNPACKED}", plan.rename().arguments().get(1));
    }

    /** The processors of NeoForge 21.1.265's profile (checked against the real installer, 2026-11-06). */
    private static final String NEOFORGE_PROFILE = """
            {
              "processors": [
                {"sides": ["server"], "jar": "net.neoforged.installertools:installertools:2.1.2",
                 "args": ["--task", "BUNDLER_EXTRACT", "--input", "{MINECRAFT_JAR}", "--output", "{MC_UNPACKED}", "--jar-only"]},
                {"jar": "net.neoforged.installertools:installertools:2.1.2",
                 "classpath": ["net.neoforged.installertools:installertools:2.1.2", "net.neoforged:srgutils:1.0.0"],
                 "args": ["--task", "MCP_DATA", "--input", "[net.neoforged:neoform:1.21.1-20240808.144430@zip]",
                          "--output", "{MAPPINGS}", "--key", "mappings"]},
                {"jar": "net.neoforged.installertools:installertools:2.1.2",
                 "args": ["--task", "DOWNLOAD_MOJMAPS", "--version", "1.21.1", "--side", "{SIDE}", "--output", "{MOJMAPS}"]},
                {"sides": ["client"], "jar": "net.neoforged.installertools:jarsplitter:2.1.2",
                 "args": ["--input", "{MINECRAFT_JAR}", "--slim", "{MC_SLIM}", "--extra", "{MC_EXTRA}", "--srg", "{MERGED_MAPPINGS}"]},
                {"sides": ["server"], "jar": "net.neoforged.installertools:jarsplitter:2.1.2",
                 "args": ["--input", "{MC_UNPACKED}", "--slim", "{MC_SLIM}", "--extra", "{MC_EXTRA}", "--srg", "{MERGED_MAPPINGS}"]},
                {"jar": "net.neoforged:AutoRenamingTool:2.0.3:all",
                 "args": ["--input", "{MC_SLIM}", "--output", "{MC_SRG}", "--names", "{MERGED_MAPPINGS}", "--ann-fix"]},
                {"jar": "net.neoforged.installertools:binarypatcher:2.1.2:fatjar",
                 "args": ["--clean", "{MC_SRG}", "--output", "{PATCHED}", "--apply", "{BINPATCH}"]}
              ]
            }
            """;

    @Test
    void neoForgeClassicProfilesRenameWithTheirOwnRenamer() {
        ForgeInstallerTools.Plan client =
                ForgeInstallerTools.plan(profile(NEOFORGE_PROFILE), "client").orElseThrow();
        ForgeInstallerTools.Plan server =
                ForgeInstallerTools.plan(profile(NEOFORGE_PROFILE), "server").orElseThrow();

        // Without the server's bundler extraction and the binary patcher: ForgePatchSet patches the result.
        assertEquals(4, client.steps().size());
        assertEquals(
                List.of("net.neoforged:AutoRenamingTool:2.0.3:all"),
                client.rename().classPath());
        assertEquals("{MC_SRG}", client.rename().arguments().get(3));
        assertEquals("{MC_UNPACKED}", server.steps().get(2).arguments().get(1));
        assertTrue(client.steps().stream().noneMatch(step -> step.arguments().contains("{PATCHED}")));
    }

    @Test
    void aProfileWithoutTheStepsHasNoPlan() {
        // Forge 26.x: the game is not obfuscated, so nothing renames it.
        String modern = """
                {"processors": [{"jar": "net.minecraftforge:binarypatcher:1.3.4", "args": ["--clean", "{MINECRAFT_JAR}"]}]}
                """;

        assertTrue(ForgeInstallerTools.plan(profile(modern), "client").isEmpty());
        assertTrue(ForgeInstallerTools.plan(profile("{}"), "client").isEmpty());
    }

    @Test
    void placeholdersAreReplacedAndUnknownOnesRefused() throws IOException {
        Map<String, String> values = Map.of("SIDE", "client");

        assertEquals("client", ForgeInstallerTools.substitute("{SIDE}", values, name -> java.nio.file.Path.of(name)));
        assertEquals(
                "--sanitize",
                ForgeInstallerTools.substitute("--sanitize", values, name -> java.nio.file.Path.of(name)));
        assertThrows(
                IOException.class,
                () -> ForgeInstallerTools.substitute("{ROOT}", values, name -> java.nio.file.Path.of(name)));
    }

    private static JsonObject profile(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
