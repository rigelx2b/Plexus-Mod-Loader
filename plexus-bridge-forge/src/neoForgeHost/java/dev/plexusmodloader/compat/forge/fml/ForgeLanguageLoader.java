package dev.plexusmodloader.compat.forge.fml;

import java.lang.annotation.ElementType;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import net.neoforged.neoforgespi.language.ModFileScanData;

/**
 * Forge on NeoForge (ADR-0051, F-N2): FML's language loader for the MinecraftForge mods {@link ForgeModsTomlReader} read.
 * It finds the mod's class by Forge's {@code @Mod} annotation in FML's scan and hands it to {@link ForgeModContainer},
 * which constructs it with Forge's own {@code FMLModContainer}.
 *
 * <p>Registered as an FML service in {@code neoforge-host.jar}; FML creates it in every NeoForge launch, so this class names
 * no Forge type: those are only in {@link ForgeModContainer}, loaded when a Forge mod is. Thread: stateless.
 */
public final class ForgeLanguageLoader implements IModLanguageLoader {

    /** The {@code modLoader} {@link ForgeModsToml} gives a Forge mod; not FML's {@code javafml}, which is NeoForge's. */
    static final String NAME = "plexus_forge_javafml";

    static final String VERSION = "1";

    /** Forge's mod annotation, {@code net.minecraftforge.fml.common.Mod}, as FML's scan records it. */
    static final String MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public ModContainer loadMod(IModInfo info, ModFileScanData scanData, ModuleLayer layer) {
        String modClass = scanData.getAnnotations().stream()
                .filter(annotation -> annotation.targetType() == ElementType.TYPE
                        && annotation.annotationType().getDescriptor().equals(MOD_ANNOTATION)
                        && info.getModId().equals(annotation.annotationData().get("value")))
                .map(annotation -> annotation.clazz().getClassName())
                .findFirst()
                .orElseThrow(
                        () -> new ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.failedtoloadmodclass")
                                .withAffectedMod(info)));
        return ForgeModContainer.create(info, modClass, layer);
    }
}
