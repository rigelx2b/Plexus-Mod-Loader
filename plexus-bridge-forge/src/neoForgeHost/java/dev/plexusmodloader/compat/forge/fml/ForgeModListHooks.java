package dev.plexusmodloader.compat.forge.fml;

import java.util.List;
import net.minecraftforge.forgespi.language.IModInfo;

/**
 * Forge on NeoForge (ADR-0051 24): what Forge's {@code ModList.getMods()} answers, which the host's patch of Forge's
 * {@code fmlcore} jar ({@code ForgeModInfosPatch} in the Forge bridge) points here instead of Forge's {@code
 * SORTED_LIST}, filled only by Forge's launch.
 *
 * <p>Thread: any.
 */
public final class ForgeModListHooks {

    private ForgeModListHooks() {}

    /**
     * The mod infos of the Forge mods the host runs, in the order of Forge's {@code ModList} ({@code
     * ForgeModList.publish}); empty before FML constructs the first Forge mod.
     */
    public static List<IModInfo> mods() {
        return ForgeModList.modInfos();
    }
}
