package dev.plexusmodloader.compat.forge.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Forge on NeoForge, F-N2: a Forge {@code mods.toml} as FML 11 reads a {@code neoforge.mods.toml}. */
class ForgeModsTomlTest {

    private static final String FORGE_MODS_TOML = """
            modLoader = "javafml"
            loaderVersion = "[65,)"
            license = "MIT"

            [[mods]]
            modId = "example"
            version = "1.0.0"

            [[dependencies.example]]
            modId = "forge"
            mandatory = true
            versionRange = "[65,)"
            ordering = "NONE"
            side = "BOTH"

            [[dependencies.example]]
            modId = "minecraft"
            mandatory = true
            versionRange = "[26.2,26.3)"
            ordering = "NONE"
            side = "BOTH"

            [[dependencies.example]]
            modId = "jei"
            mandatory = false
            versionRange = "*"
            ordering = "AFTER"
            side = "CLIENT"
            """;

    @Test
    void theLanguageLoaderIsPlexusModLoadersForgeLoader() {
        CommentedConfig config = parse();
        ForgeModsToml.translate(config);
        assertEquals(ForgeLanguageLoader.NAME, config.get("modLoader"));
        assertEquals("[" + ForgeLanguageLoader.VERSION + ",)", config.get("loaderVersion"));
    }

    /** MinecraftForge itself is present as libraries; the other dependencies keep their range and get FML 11's type. */
    @Test
    void theForgeDependencyIsDroppedAndMandatoryBecomesType() {
        CommentedConfig config = parse();
        ForgeModsToml.translate(config);
        List<Config> dependencies = config.get(List.of("dependencies", "example"));
        assertEquals(
                List.of("minecraft", "jei"),
                dependencies.stream().map(d -> (String) d.get("modId")).toList());
        assertEquals("required", dependencies.get(0).get("type"));
        assertEquals("[26.2,26.3)", dependencies.get(0).get("versionRange"));
        assertEquals("optional", dependencies.get(1).get("type"));
        assertFalse(dependencies.get(0).contains("mandatory"));
        assertFalse(dependencies.get(1).contains("mandatory"));
    }

    private static CommentedConfig parse() {
        return TomlFormat.instance().createParser().parse(FORGE_MODS_TOML);
    }
}
