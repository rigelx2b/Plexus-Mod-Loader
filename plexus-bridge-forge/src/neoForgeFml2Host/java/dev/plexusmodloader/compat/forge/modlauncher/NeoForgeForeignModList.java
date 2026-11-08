package dev.plexusmodloader.compat.forge.modlauncher;

import dev.plexusmodloader.compat.forge.ForeignModIndex;
import java.util.HashMap;
import java.util.Map;
import net.neoforged.neoforgespi.language.IModFileInfo;

/**
 * Plexus Mod Loader's answers to NeoForge's FML mod list for the mods it runs beside NeoForge (Plexus Mods and other
 * loaders' mods; ADR-0025, ADR-0026). NeoForge's {@code ModList} calls {@link #getModFileById} and {@link #isLoaded} only
 * when its own answer is {@code null} or {@code false} (Plexus Mod Loader's {@code ForgeModListPatch}, shape {@code
 * NEOFORGE_MODLAUNCHER}), so a NeoForge mod keeps its ID. This one is for FML 2, on ModLauncher (ADR-0043).
 *
 * <p>Lives in the BOOT layer's {@code cpw.mods.modlauncher} module, which exports this package; FML's {@code
 * fml_loader}, an automatic module of the BOOT layer, reads it.
 *
 * <p>Thread: {@link #install} runs once on the launching thread before FML starts; the lookups may run on any thread
 * afterwards.
 */
public final class NeoForgeForeignModList {

    private static volatile Map<String, Fml2ForeignModFileInfo> files = Map.of();

    private NeoForgeForeignModList() {}

    /** Shows {@code mods} to FML's mod list, replacing what an earlier call showed. */
    static void install(ForeignModIndex mods) {
        Map<String, Fml2ForeignModFileInfo> byId = new HashMap<>();
        for (ForeignModIndex.ForeignMod mod : mods.mods()) {
            Fml2ForeignModFileInfo file = new Fml2ForeignModFileInfo(mod);
            mod.ids().forEach(id -> byId.put(id, file));
        }
        files = Map.copyOf(byId);
    }

    /**
     * The mod file of the Plexus Mod or other loader's mod with this ID (its own or a provided one), or {@code null}, as
     * {@code ModList.getModFileById} answers for a missing mod.
     */
    public static IModFileInfo getModFileById(String id) {
        return id == null ? null : files.get(id);
    }

    /** Whether a Plexus Mod or other loader's mod has this ID, its own or a provided one. */
    public static boolean isLoaded(String id) {
        return id != null && files.containsKey(id);
    }
}
