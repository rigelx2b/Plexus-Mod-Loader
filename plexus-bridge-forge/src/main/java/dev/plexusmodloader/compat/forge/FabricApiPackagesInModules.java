package dev.plexusmodloader.compat.forge;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * FML's module of a mod that carries classes in Fabric Loader's API packages, for a launch where Plexus Mod Loader runs
 * Fabric mods next to the host's (ADR-0043 point 10). CreativeCore 2.11.26 for NeoForge 20.4 has a copy of {@code
 * net.fabricmc.api.Environment} and {@code EnvType}; as a module of FML's game layer it owns {@code net.fabricmc.api},
 * and the game class loader then looks for every class of that package in the mod's jar ({@code NoClassDefFoundError:
 * net/fabricmc/api/ModInitializer} for the Fabric mods' entrypoints). Such a module leaves those packages to Plexus Mod
 * Loader's Fabric Loader API, which has the same annotations.
 *
 * <p>Thread: FML's startup thread; no state.
 */
final class FabricApiPackagesInModules {

    /** The packages of Fabric Loader's API that Plexus Mod Loader's Fabric bridge provides, and those below them. */
    static final List<String> PACKAGES = List.of("net.fabricmc.api", "net.fabricmc.loader.api");

    private FabricApiPackagesInModules() {}

    /** Whether {@code name} is one of {@link #PACKAGES} or below one. */
    static boolean isFabricApi(String name) {
        return PACKAGES.stream().anyMatch(api -> name.equals(api) || name.startsWith(api + "."));
    }

    /**
     * {@code reference} without the {@link #PACKAGES} it has, or {@code reference} itself when it has none; {@code
     * report} gets the packages left out.
     */
    static ModuleReference without(ModuleReference reference, Consumer<Set<String>> report) {
        ModuleDescriptor descriptor = reference.descriptor();
        Set<String> dropped = new LinkedHashSet<>();
        Set<String> kept = new LinkedHashSet<>();
        for (String name : descriptor.packages()) {
            (isFabricApi(name) ? dropped : kept).add(name);
        }
        if (dropped.isEmpty()) {
            return reference;
        }
        ModuleDescriptor.Builder builder;
        if (descriptor.isAutomatic()) {
            builder = ModuleDescriptor.newAutomaticModule(descriptor.name());
        } else {
            builder = descriptor.isOpen()
                    ? ModuleDescriptor.newOpenModule(descriptor.name())
                    : ModuleDescriptor.newModule(descriptor.name());
            descriptor.requires().forEach(builder::requires);
            descriptor.exports().stream()
                    .filter(exports -> !dropped.contains(exports.source()))
                    .forEach(builder::exports);
            descriptor.opens().stream()
                    .filter(opens -> !dropped.contains(opens.source()))
                    .forEach(builder::opens);
            descriptor.uses().forEach(builder::uses);
        }
        descriptor.version().ifPresent(builder::version);
        for (ModuleDescriptor.Provides provides : descriptor.provides()) {
            List<String> providers = provides.providers().stream()
                    .filter(provider -> !dropped.contains(packageOf(provider)))
                    .toList();
            if (!providers.isEmpty()) {
                builder.provides(provides.service(), providers);
            }
        }
        builder.packages(kept);
        descriptor
                .mainClass()
                .filter(main -> !dropped.contains(packageOf(main)))
                .ifPresent(builder::mainClass);
        report.accept(dropped);
        ModuleDescriptor without = builder.build();
        return new ModuleReference(without, reference.location().orElse(null)) {
            @Override
            public ModuleReader open() throws IOException {
                return reference.open();
            }
        };
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }
}
