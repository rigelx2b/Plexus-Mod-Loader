package dev.plexusmodloader.game.layer.v1_21_1.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.resource.ResourceId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClientHooksTest {

    /** 1.21.1 keeps post effect chains in {@code shaders/post/}, where 1.21.11 uses {@code post_effect/}. */
    @Test
    void postEffectsMapToTheChainFileAndBack() {
        ResourceId effect = ResourceId.of("my_mod", "blur");

        ResourceId file = ClientHooks.postEffectFile(effect);

        assertEquals(ResourceId.of("my_mod", "shaders/post/blur.json"), file);
        assertEquals(Optional.of(effect), ClientHooks.postEffectId(file));
    }

    @Test
    void aChainOutsideTheModPostDirectoryHasNoModFacingId() {
        assertTrue(ClientHooks.postEffectId(ResourceId.of("minecraft", "shaders/program/blur.json"))
                .isEmpty());
        assertTrue(ClientHooks.postEffectId(ResourceId.of("minecraft", "shaders/post/creeper.txt"))
                .isEmpty());
    }

    @Test
    void nestedEffectPathsRoundTrip() {
        ResourceId effect = ResourceId.of("my_mod", "fx/underwater");

        assertEquals(Optional.of(effect), ClientHooks.postEffectId(ClientHooks.postEffectFile(effect)));
    }
}
