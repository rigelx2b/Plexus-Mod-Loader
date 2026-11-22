package dev.plexusmodloader.compat.forge.fml;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.neoforged.fml.jarcontents.JarResource;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.NightConfigWrapper;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.InvalidModFileException;

/**
 * Forge on NeoForge (ADR-0051, F-N2): a MinecraftForge mod's {@code META-INF/mods.toml} as FML 11 reads a {@code
 * neoforge.mods.toml}, parsed with FML's own TOML parser and mod file classes, with four changes ({@link #translate}):
 * the language loader is Plexus Mod Loader's ({@link ForgeLanguageLoader}), the dependencies on MinecraftForge itself are
 * dropped (its API is in the game as libraries), and each Forge {@code mandatory} flag becomes FML 11's {@code type}.
 *
 * <p>Thread: stateless.
 */
final class ForgeModsToml {

    static final String FORGE_METADATA = "META-INF/mods.toml";
    static final String NEOFORGE_METADATA = "META-INF/neoforge.mods.toml";

    /** The mod ID of MinecraftForge itself, which Forge mods depend on. */
    static final String FORGE = "forge";

    private ForgeModsToml() {}

    /** FML's mod file information for {@code file}'s translated {@code mods.toml}. */
    static IModFileInfo modFileInfo(IModFile file) throws InvalidModFileException {
        JarResource resource = file.getContents().get(FORGE_METADATA);
        CommentedConfig config;
        try (BufferedReader reader = resource.bufferedReader()) {
            config = TomlFormat.instance().createParser().parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(file.getFilePath() + " " + FORGE_METADATA, e);
        }
        translate(config);
        addMixins(config, FmlSharedMixin.manifestConfigs(file.getContents().getManifest()));
        NightConfigWrapper wrapper = new NightConfigWrapper(config.unmodifiable());
        return new ModFileInfo((ModFile) file, wrapper, wrapper::setFile);
    }

    /**
     * Sets {@code config}'s {@code [[mixins]]} entries to {@code manifestConfigs}, in order: Forge applies only the Mixin
     * configurations of a mod's manifest ({@code MixinConfigs}), while FML 11 and 12 apply a mod file's from its {@code
     * [[mixins]]} entries, as for NeoForge's mods. A Forge mod's own {@code [[mixins]]} entries, which Forge ignores, are
     * dropped: the corpus's boatiview 0.0.9 names a configuration there that its jar does not have, and FML stopped the
     * game on it.
     */
    static void addMixins(Config config, List<String> manifestConfigs) {
        config.remove("mixins");
        if (manifestConfigs.isEmpty()) {
            return;
        }
        List<Object> mixins = new ArrayList<>();
        for (String name : new LinkedHashSet<>(manifestConfigs)) {
            Config entry = config.createSubConfig();
            entry.set("config", name);
            mixins.add(entry);
        }
        config.set("mixins", mixins);
    }

    /** Changes {@code config}, a Forge {@code mods.toml}, into what FML 11 reads from a {@code neoforge.mods.toml}. */
    static void translate(Config config) {
        config.set("modLoader", ForgeLanguageLoader.NAME);
        config.set("loaderVersion", "[" + ForgeLanguageLoader.VERSION + ",)");
        Object dependencies = config.get("dependencies");
        if (!(dependencies instanceof Config byMod)) {
            return;
        }
        for (Config.Entry entry : byMod.entrySet()) {
            if (!(entry.getValue() instanceof List<?> list)) {
                continue;
            }
            List<Object> kept = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Config dependency) {
                    if (FORGE.equals(dependency.get("modId"))) {
                        continue;
                    }
                    Object mandatory = dependency.remove("mandatory");
                    if (!dependency.contains("type")) {
                        dependency.set("type", Boolean.FALSE.equals(mandatory) ? "optional" : "required");
                    }
                }
                kept.add(element);
            }
            entry.setValue(kept);
        }
    }
}
