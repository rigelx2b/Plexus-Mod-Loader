package dev.plexusmodloader.compat.forge.fml;

import cpw.mods.jarhandling.SecureJar;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import net.minecraftforge.forgespi.language.IConfigurable;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.IModLanguageProvider;
import net.minecraftforge.forgespi.language.ModFileScanData;
import net.minecraftforge.forgespi.locating.ForgeFeature;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModProvider;
import org.apache.maven.artifact.versioning.ArtifactVersion;

/**
 * Forge on NeoForge (ADR-0051, F-N2): FML's information about a MinecraftForge mod as Forge's SPI ({@code forgespi}) has
 * it, for Forge's own {@code FMLModContainer}. It answers what that container reads: the mod's ID, version, namespace and
 * configuration, its file's module name (the module FML defined in its game layer) and the jar's manifest ({@code
 * Add-Opens}, {@code Add-Exports}).
 *
 * <p>What only Forge's own loader has for its files (its language providers, scan, locator, signatures) throws {@link
 * UnsupportedOperationException} naming the method, rather than answering with a made-up value.
 *
 * <p>Thread: immutable; the manifest is read once, on first use.
 */
final class ForgeModView implements IModInfo {

    private final net.neoforged.neoforgespi.language.IModInfo info;
    private final FileInfo file;

    ForgeModView(net.neoforged.neoforgespi.language.IModInfo info) {
        this.info = info;
        this.file = new FileInfo();
    }

    @Override
    public IModFileInfo getOwningFile() {
        return file;
    }

    @Override
    public String getModId() {
        return info.getModId();
    }

    @Override
    public String getDisplayName() {
        return info.getDisplayName();
    }

    @Override
    public String getDescription() {
        return info.getDescription();
    }

    @Override
    public ArtifactVersion getVersion() {
        return info.getVersion();
    }

    @Override
    public List<? extends ModVersion> getDependencies() {
        return List.of();
    }

    @Override
    public List<? extends ForgeFeature.Bound> getForgeFeatures() {
        return List.of();
    }

    @Override
    public String getNamespace() {
        return info.getNamespace();
    }

    @Override
    public Map<String, Object> getModProperties() {
        return info.getModProperties();
    }

    @Override
    public Optional<URL> getUpdateURL() {
        return info.getUpdateURL();
    }

    @Override
    public Optional<URL> getModURL() {
        return info.getModURL();
    }

    @Override
    public Optional<String> getLogoFile() {
        return info.getLogoFile();
    }

    @Override
    public boolean getLogoBlur() {
        return info.getLogoBlur();
    }

    @Override
    public IConfigurable getConfig() {
        return new Configurable(info.getConfig());
    }

    @Override
    public String toString() {
        return info.getModId() + " " + info.getVersion();
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(method);
    }

    /** FML's configuration of the mod, as Forge's {@link IConfigurable}. */
    private record Configurable(net.neoforged.neoforgespi.language.IConfigurable config) implements IConfigurable {

        @Override
        public <T> Optional<T> getConfigElement(String... key) {
            return config.getConfigElement(key);
        }

        @Override
        public List<? extends IConfigurable> getConfigList(String... key) {
            return config.getConfigList(key).stream().map(Configurable::new).toList();
        }
    }

    private final class FileInfo implements IModFileInfo {

        private final ModFile modFile = new ModFile();

        private net.neoforged.neoforgespi.language.IModFileInfo owner() {
            return info.getOwningFile();
        }

        @Override
        public List<IModInfo> getMods() {
            return List.of(ForgeModView.this);
        }

        @Override
        public List<LanguageSpec> requiredLanguageLoaders() {
            return List.of();
        }

        @Override
        public boolean showAsResourcePack() {
            return owner().showAsResourcePack();
        }

        @Override
        public Map<String, Object> getFileProperties() {
            return owner().getFileProperties();
        }

        @Override
        public String getLicense() {
            return owner().getLicense();
        }

        @Override
        public String moduleName() {
            // FML defines each mod file as the module named by its ID, as its own FMLModContainer finds it.
            return owner().getFile().getId();
        }

        @Override
        public String versionString() {
            return owner().versionString();
        }

        @Override
        public List<String> usesServices() {
            return owner().usesServices();
        }

        @Override
        public IModFile getFile() {
            return modFile;
        }

        @Override
        public IConfigurable getConfig() {
            return new Configurable(owner().getConfig());
        }
    }

    private final class ModFile implements IModFile {

        private final ManifestJar jar =
                new ManifestJar(info.getOwningFile().getFile().getFilePath());

        @Override
        public List<IModLanguageProvider> getLoaders() {
            throw unsupported("getLoaders");
        }

        @Override
        public Path findResource(String... path) {
            throw unsupported("findResource");
        }

        @Override
        public Supplier<Map<String, Object>> getSubstitutionMap() {
            return Map::of;
        }

        @Override
        public Type getType() {
            return Type.MOD;
        }

        @Override
        public Path getFilePath() {
            return jar.getPrimaryPath();
        }

        @Override
        public SecureJar getSecureJar() {
            return jar;
        }

        @Override
        public void setSecurityStatus(SecureJar.Status status) {
            throw unsupported("setSecurityStatus");
        }

        @Override
        public List<IModInfo> getModInfos() {
            return file.getMods();
        }

        @Override
        public ModFileScanData getScanResult() {
            throw unsupported("getScanResult");
        }

        @Override
        public String getFileName() {
            return jar.getPrimaryPath().getFileName().toString();
        }

        @Override
        public IModProvider getProvider() {
            throw unsupported("getProvider");
        }

        @Override
        public IModFileInfo getModFileInfo() {
            return file;
        }
    }

    /**
     * The mod's jar as Forge's {@link SecureJar}, for the one thing {@code FMLModContainer} reads from it: the manifest,
     * through {@link #moduleDataProvider()}.
     */
    private static final class ManifestJar implements SecureJar, SecureJar.ModuleDataProvider {

        private final Path jar;
        private Manifest manifest;

        ManifestJar(Path jar) {
            this.jar = jar;
        }

        @Override
        public ModuleDataProvider moduleDataProvider() {
            return this;
        }

        @Override
        public synchronized Manifest getManifest() {
            if (manifest == null) {
                try (JarFile file = new JarFile(jar.toFile())) {
                    Manifest read = file.getManifest();
                    manifest = read != null ? read : new Manifest();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return manifest;
        }

        @Override
        public Path getPrimaryPath() {
            return jar;
        }

        @Override
        public String name() {
            return jar.getFileName().toString();
        }

        @Override
        public CodeSigner[] getManifestSigners() {
            throw unsupported("getManifestSigners");
        }

        @Override
        public Status verifyPath(Path path) {
            throw unsupported("verifyPath");
        }

        @Override
        public Status getFileStatus(String name) {
            throw unsupported("getFileStatus");
        }

        @Override
        public Attributes getTrustedManifestEntries(String name) {
            throw unsupported("getTrustedManifestEntries");
        }

        @Override
        public boolean hasSecurityData() {
            return false;
        }

        @Override
        public Set<String> getPackages() {
            throw unsupported("getPackages");
        }

        @Override
        public List<Provider> getProviders() {
            throw unsupported("getProviders");
        }

        @Override
        public Path getPath(String first, String... rest) {
            throw unsupported("getPath");
        }

        @Override
        public Path getRootPath() {
            throw unsupported("getRootPath");
        }

        @Override
        public ModuleDescriptor descriptor() {
            throw unsupported("descriptor");
        }

        @Override
        public URI uri() {
            return jar.toUri();
        }

        @Override
        public Optional<URI> findFile(String name) {
            throw unsupported("findFile");
        }

        @Override
        public Optional<InputStream> open(String name) {
            throw unsupported("open");
        }

        @Override
        public CodeSigner[] verifyAndGetSigners(String name, byte[] bytes) {
            throw unsupported("verifyAndGetSigners");
        }
    }
}
