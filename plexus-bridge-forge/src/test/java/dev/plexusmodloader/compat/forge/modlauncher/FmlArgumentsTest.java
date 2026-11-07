package dev.plexusmodloader.compat.forge.modlauncher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** ADR-0043: the options FML 4 reads are not passed on to the game. */
class FmlArgumentsTest {

    @Test
    void fmlOptionsAreRemovedWithTheirValues() {
        String[] args = {
            "--fml.neoForgeVersion", "21.1.265", "--universe", "universe", "--fml.mcVersion", "1.21.1", "--nogui"
        };

        assertArrayEquals(new String[] {"--universe", "universe", "--nogui"}, FmlArguments.withoutFml(args));
    }
}
