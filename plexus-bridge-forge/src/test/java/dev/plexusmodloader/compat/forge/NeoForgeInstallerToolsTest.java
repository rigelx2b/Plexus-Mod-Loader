package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ADR-0038: NeoForge 21.11's {@code install_profile.json} renames the obfuscated jar with {@code PROCESS_MINECRAFT_JAR};
 * the versions from 26.1 on have no such step. The profile below has the shape of NeoForge 21.11.48's (checked against
 * the real installer, 2026-11-05).
 */
class NeoForgeInstallerToolsTest {

    private static final String PROFILE = """
            {
              "data": {
                "BINPATCH": {"client": "/data/client.lzma", "server": "/data/server.lzma"},
                "MOJMAPS": {"client": "[net.minecraft:client:1.21.11:mappings@txt]"}
              },
              "processors": [
                {"jar": "net.neoforged.installertools:installertools:4.0.6:fatjar", "sides": ["server"],
                 "args": ["--task", "EXTRACT_FILES", "--archive", "{INSTALLER}"]},
                {"jar": "net.neoforged.installertools:installertools:4.0.6:fatjar",
                 "args": ["--task", "DOWNLOAD_MOJMAPS", "--version", "1.21.11", "--side", "{SIDE}"]},
                {"jar": "net.neoforged.installertools:installertools:4.0.6:fatjar",
                 "args": ["--task", "PROCESS_MINECRAFT_JAR", "--input", "{MINECRAFT_JAR}", "--input-mappings",
                          "{MOJMAPS}", "--output", "{PATCHED}", "--extract-libraries-to", "{ROOT}/libraries/",
                          "--neoform-data", "[net.neoforged:neoform:1.21.11-20251209.172050:mappings@tsrg.lzma]",
                          "--apply-patches", "{BINPATCH}"]}
              ]
            }
            """;

    @Test
    void theRenamingStepIsFoundWithItsToolPatchesAndNeoFormData() {
        Optional<NeoForgeInstallerTools.Plan> plan = NeoForgeInstallerTools.plan(profile(PROFILE), "client");

        assertTrue(plan.isPresent());
        assertEquals(
                "net.neoforged.installertools:installertools:4.0.6:fatjar",
                plan.get().toolsLibrary());
        assertEquals(
                "net.neoforged:neoform:1.21.11-20251209.172050:mappings@tsrg.lzma",
                plan.get().neoformData());
        assertEquals("data/client.lzma", plan.get().patches());
    }

    @Test
    void theServerUsesItsOwnPatchesEntry() {
        assertEquals(
                "data/server.lzma",
                NeoForgeInstallerTools.plan(profile(PROFILE), "server")
                        .orElseThrow()
                        .patches());
    }

    @Test
    void aProfileWithoutInputMappingsHasNoRenamingStep() {
        // NeoForge 26.x: the jar is not obfuscated, PROCESS_MINECRAFT_JAR (if any) has no --input-mappings.
        String modern = """
                {"data": {"BINPATCH": {"client": "/data/client.lzma"}},
                 "processors": [{"jar": "x", "args": ["--task", "PROCESS_MINECRAFT_JAR", "--input", "{MINECRAFT_JAR}",
                                                      "--apply-patches", "{BINPATCH}"]}]}
                """;

        assertTrue(NeoForgeInstallerTools.plan(profile(modern), "client").isEmpty());
        assertTrue(NeoForgeInstallerTools.plan(profile("{\"libraries\": []}"), "client")
                .isEmpty());
    }

    @Test
    void aProcessorForTheOtherSideIsSkipped() {
        String serverOnly = """
                {"data": {"BINPATCH": {"server": "/data/server.lzma"}},
                 "processors": [{"jar": "x", "sides": ["server"],
                   "args": ["--task", "PROCESS_MINECRAFT_JAR", "--input-mappings", "{M}",
                            "--neoform-data", "[a:b:c]", "--apply-patches", "{BINPATCH}"]}]}
                """;

        assertTrue(NeoForgeInstallerTools.plan(profile(serverOnly), "client").isEmpty());
        assertEquals(
                "data/server.lzma",
                NeoForgeInstallerTools.plan(profile(serverOnly), "server")
                        .orElseThrow()
                        .patches());
    }

    private static JsonObject profile(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
