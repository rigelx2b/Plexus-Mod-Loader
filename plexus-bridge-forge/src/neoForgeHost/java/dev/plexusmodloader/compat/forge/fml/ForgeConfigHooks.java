package dev.plexusmodloader.compat.forge.fml;

import java.util.function.Supplier;
import net.minecraftforge.fml.config.IConfigEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLConfig;

/**
 * Forge on NeoForge, F-N6 (ADR-0051): what Forge's config classes read from Forge's launch state in their static
 * initializers, which the host's patch of Forge's {@code fmlcore} jar ({@code ForgeConfigPatch} in the Forge bridge)
 * points here.
 *
 * <p>Thread: any; stateless.
 */
public final class ForgeConfigHooks {

    private static final String DEFAULT_CONFIG_PATH = "DEFAULT_CONFIG_PATH";

    private ForgeConfigHooks() {}

    /**
     * In place of Forge's {@code Bindings.getConfigConfiguration()} in {@code IConfigEvent}: the factories of Forge's
     * config events, as Forge's own provider ({@code ForgeBindings}) makes them.
     */
    public static Supplier<IConfigEvent.ConfigConfig> configConfiguration() {
        return () -> new IConfigEvent.ConfigConfig(
                ModConfigEvent.Loading::new, ModConfigEvent.Reloading::new, ModConfigEvent.Unloading::new);
    }

    /**
     * In place of Forge's {@code FMLConfig.getConfigValue(DEFAULT_CONFIG_PATH)} in {@code ConfigFileTypeHandler}: the
     * folder of default config files, relative to the game directory, as NeoForge's FML has it set ({@code
     * defaultconfigs} unless a player changed it); Forge's own default is the same.
     *
     * @param value Forge's {@code FMLConfig.ConfigValue} the call names
     * @throws IllegalStateException when the call names another of Forge's settings
     */
    public static String defaultConfigPath(Object value) {
        if (!(value instanceof Enum<?> setting) || !setting.name().equals(DEFAULT_CONFIG_PATH)) {
            throw new IllegalStateException("PLEXUS-COMPAT-230 Forge's ConfigFileTypeHandler asked for its setting "
                    + value + ", not " + DEFAULT_CONFIG_PATH);
        }
        return FMLConfig.defaultConfigPath();
    }
}
