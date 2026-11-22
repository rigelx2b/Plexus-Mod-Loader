package dev.plexusmodloader.compat.forge.fml;

import java.util.function.Supplier;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Forge on NeoForge, F-N6 (ADR-0051): what Forge's {@code Mod.EventBusSubscriber.Bus} calls in place of Forge's {@code
 * Bindings.getForgeBus()}, which needs Forge's launch. The host's patch of Forge's {@code javafmllanguage} jar ({@code
 * ForgeEventBusPatch} in the Forge bridge) points that call here.
 *
 * <p>Thread: any; stateless.
 */
public final class ForgeEventBusHooks {

    private ForgeEventBusHooks() {}

    /** Forge's game bus group, as Forge's own bindings provider ({@code ForgeBindings}) gives it: {@code BusGroup.DEFAULT}. */
    public static Supplier<BusGroup> forgeBus() {
        return () -> BusGroup.DEFAULT;
    }
}
