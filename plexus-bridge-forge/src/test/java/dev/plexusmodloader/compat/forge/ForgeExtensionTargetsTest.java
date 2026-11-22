package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.runtime.compat.ForgeOnNeoForgeExtensions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Forge on NeoForge, F-N5: every Forge extension interface of the Forge releases Plexus Mod Loader pins for Minecraft
 * 26.1, 26.1.1, 26.1.2, 26.2 and 26.3 ({@code plexus.forgeEventJars}) has a game class, so that the link check judges
 * the overrides of all of them.
 */
class ForgeExtensionTargetsTest {

    @ParameterizedTest
    @ValueSource(strings = {"26.1", "26.1.1", "26.1.2", "26.2", "26.3"})
    void everyExtensionInterfaceHasAGameClass(String minecraft) throws IOException {
        List<ForgeOnNeoForgeExtensions.Extension> extensions =
                ForgeOnNeoForgeExtensions.extensions(forgeJar(minecraft));
        assertTrue(extensions.size() > 30, () -> extensions.size() + " extension interfaces");
        assertEquals(
                List.of(),
                extensions.stream()
                        .filter(extension -> extension.target().isEmpty())
                        .map(ForgeOnNeoForgeExtensions.Extension::name)
                        .toList());
        assertTrue(
                extensions.stream()
                        .anyMatch(
                                extension -> extension.name().equals("net/minecraftforge/common/extensions/IForgeItem")
                                        && extension.target().orElseThrow().equals("net/minecraft/world/item/Item")
                                        && extension
                                                .methods()
                                                .contains(
                                                        "getEntityLifespan(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;)I")));
    }

    private static Path forgeJar(String minecraft) {
        String jars = System.getProperty("plexus.forgeEventJars");
        String prefix = "forge-" + minecraft + "-";
        return Arrays.stream(jars.split(File.pathSeparator))
                .map(Path::of)
                .filter(jar -> jar.getFileName().toString().startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + prefix + "* in " + jars));
    }
}
