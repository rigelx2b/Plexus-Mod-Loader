package dev.plexusmodloader.compat.forge.modlauncher;

import cpw.mods.jarhandling.SecureJar;
import dev.plexusmodloader.compat.forge.ForeignModIndex;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageProvider;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModProvider;

/**
 * The jar of a Plexus Mod or other loader's mod as a NeoForge FML mod file (ADR-0025, ADR-0026): its ID, path, name and
 * contents. FML did not locate or scan it, so {@link #getScanResult} throws {@link UnsupportedOperationException} with
 * the reason, rather than answering with a made-up value.
 *
 * <p>Thread: safe; the jar is opened once, on the first {@link #getSecureJar}.
 */
final class Fml2ForeignModFile implements IModFile {

    private final ForeignModIndex.ForeignMod mod;
    private final IModFileInfo info;
    private SecureJar jar;

    Fml2ForeignModFile(ForeignModIndex.ForeignMod mod, IModFileInfo info) {
        this.mod = mod;
        this.info = info;
    }

    @Override
    public synchronized SecureJar getSecureJar() {
        if (jar == null) {
            jar = SecureJar.from(mod.jar());
        }
        return jar;
    }

    /** FML did not verify the jar: its own loader runs it, and the status is not kept. */
    @Override
    public void setSecurityStatus(SecureJar.Status status) {}

    @Override
    public Path findResource(String... path) {
        return getSecureJar().getPath(String.join("/", path));
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
        return mod.jar();
    }

    @Override
    public List<IModInfo> getModInfos() {
        return info.getMods();
    }

    @Override
    public ModFileScanData getScanResult() {
        throw new UnsupportedOperationException(mod.loader() + " mod " + mod.modId() + " was not scanned by FML:"
                + " Plexus Mod Loader runs it beside NeoForge, and FML's mod list shows it for presence queries only"
                + " (ADR-0025)");
    }

    @Override
    public String getFileName() {
        return mod.jar().getFileName().toString();
    }

    /** No FML language provider: FML never constructs the mod. */
    @Override
    public List<IModLanguageProvider> getLoaders() {
        return List.of();
    }

    @Override
    public IModProvider getProvider() {
        throw new UnsupportedOperationException(mod.loader() + " mod " + mod.modId() + " was not located by FML:"
                + " Plexus Mod Loader runs it beside NeoForge, and FML's mod list shows it for presence queries only"
                + " (ADR-0025)");
    }

    @Override
    public IModFileInfo getModFileInfo() {
        return info;
    }
}
