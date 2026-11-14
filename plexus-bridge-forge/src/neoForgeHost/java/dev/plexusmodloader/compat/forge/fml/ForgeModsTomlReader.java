package dev.plexusmodloader.compat.forge.fml;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModJarMetadata;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileReader;
import net.neoforged.neoforgespi.locating.IOrderedProvider;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * Forge on NeoForge (ADR-0051, F-N2): FML's reader for the MinecraftForge mods Plexus Mod Loader handed to it ({@link
 * PlexusFml#isForgeMod}). FML 11 reads only {@code META-INF/neoforge.mods.toml}; this reader gives FML the mod file of a
 * Forge jar from its {@code META-INF/mods.toml} ({@link ForgeModsToml}), with Plexus Mod Loader's Forge language loader
 * ({@link ForgeLanguageLoader}). Every other jar is left to FML's own readers.
 *
 * <p>Registered as an FML service in {@code neoforge-host.jar}; FML creates it during discovery. Thread: stateless.
 */
public final class ForgeModsTomlReader implements IModFileReader {

    @Override
    public IModFile read(JarContents contents, ModFileDiscoveryAttributes attributes) {
        if (!PlexusFml.isForgeMod(contents.getPrimaryPath())
                || !contents.containsFile(ForgeModsToml.FORGE_METADATA)
                || contents.containsFile(ForgeModsToml.NEOFORGE_METADATA)) {
            return null;
        }
        // As FML's own reader of neoforge.mods.toml: the jar's module is named by its first mod ID, which FML's and
        // Forge's mod containers look the module up by.
        ModJarMetadata metadata = new ModJarMetadata();
        ModFile file = new ModFile(contents, metadata, ForgeModsToml::modFileInfo, attributes.withReader(this));
        metadata.setModFile(file);
        return file;
    }

    /** Before FML's own readers, which would take a jar without {@code neoforge.mods.toml} for a library. */
    @Override
    public int getPriority() {
        return IOrderedProvider.HIGHEST_SYSTEM_PRIORITY;
    }

    @Override
    public String toString() {
        return "Plexus Mod Loader's Forge mods.toml reader";
    }
}
