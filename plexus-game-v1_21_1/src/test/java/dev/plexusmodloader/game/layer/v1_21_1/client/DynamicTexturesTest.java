package dev.plexusmodloader.game.layer.v1_21_1.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DynamicTexturesTest {

    /** 1.21.1's {@code NativeImage.setPixelRGBA} wants {@code 0xAABBGGRR}; mods give {@code 0xAARRGGBB}. */
    @Test
    void swapsRedAndBlueAndKeepsAlphaAndGreen() {
        assertEquals(0xFF0000FF, DynamicTextures.abgr(0xFFFF0000), "opaque red");
        assertEquals(0x80FF1020, DynamicTextures.abgr(0x802010FF), "translucent blue-ish");
        assertEquals(0xFF00FF00, DynamicTextures.abgr(0xFF00FF00), "green stays");
        assertEquals(0, DynamicTextures.abgr(0));
    }
}
