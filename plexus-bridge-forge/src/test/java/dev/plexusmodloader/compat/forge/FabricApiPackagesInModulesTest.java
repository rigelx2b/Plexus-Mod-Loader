package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** ADR-0043 point 10: a NeoForge mod's copy of Fabric Loader's API classes does not own their packages. */
class FabricApiPackagesInModulesTest {

    @Test
    void anAutomaticModuleLeavesFabricApiPackagesOut() {
        // CreativeCore 2.11.26 for NeoForge 20.4 has net.fabricmc.api.Environment and EnvType.
        ModuleReference creativeCore = reference(ModuleDescriptor.newAutomaticModule("creativecore")
                .packages(Set.of("team.creative.creativecore", "net.fabricmc.api"))
                .build());
        List<Set<String>> reported = new ArrayList<>();

        ModuleReference result = FabricApiPackagesInModules.without(creativeCore, reported::add);

        assertTrue(result.descriptor().isAutomatic());
        assertEquals(Set.of("team.creative.creativecore"), result.descriptor().packages());
        assertEquals(List.of(Set.of("net.fabricmc.api")), reported);
    }

    @Test
    void anExplicitModuleKeepsItsOtherExportsAndServices() {
        ModuleReference mod = reference(ModuleDescriptor.newModule("example")
                .packages(Set.of("com.example", "net.fabricmc.loader.api.metadata"))
                .exports("com.example")
                .exports("net.fabricmc.loader.api.metadata")
                .provides("com.example.Service", List.of("com.example.Impl"))
                .build());

        ModuleDescriptor result =
                FabricApiPackagesInModules.without(mod, dropped -> {}).descriptor();

        assertEquals(Set.of("com.example"), result.packages());
        assertEquals(1, result.exports().size());
        assertEquals("com.example", result.exports().iterator().next().source());
        assertEquals(1, result.provides().size());
    }

    @Test
    void aModuleWithoutThemStaysAsItIs() {
        ModuleReference mod = reference(ModuleDescriptor.newAutomaticModule("other")
                .packages(Set.of("net.fabricmc.fabric.api.event", "com.example"))
                .build());
        assertSame(mod, FabricApiPackagesInModules.without(mod, dropped -> {}));
        assertFalse(FabricApiPackagesInModules.isFabricApi("net.fabricmc.fabric.api.event"));
        assertTrue(FabricApiPackagesInModules.isFabricApi("net.fabricmc.loader.api"));
        assertFalse(FabricApiPackagesInModules.isFabricApi("net.fabricmc.apix"));
    }

    private static ModuleReference reference(ModuleDescriptor descriptor) {
        return new ModuleReference(descriptor, null) {
            @Override
            public ModuleReader open() {
                return new ModuleReader() {
                    @Override
                    public Optional<URI> find(String name) {
                        return Optional.empty();
                    }

                    @Override
                    public Stream<String> list() {
                        return Stream.empty();
                    }

                    @Override
                    public void close() {}
                };
            }
        };
    }
}
