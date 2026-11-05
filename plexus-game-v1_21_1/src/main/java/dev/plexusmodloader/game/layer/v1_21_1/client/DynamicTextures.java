package dev.plexusmodloader.game.layer.v1_21_1.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.plexusmodloader.game.layer.v1_21_1.Views;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.DynamicTextureDeclaration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** The game textures behind mods' dynamic textures, created on first upload. */
public final class DynamicTextures {

    private static final Map<ResourceLocation, DynamicTexture> TEXTURES = new ConcurrentHashMap<>();

    private DynamicTextures() {}

    /** Copies {@code pixels} (rows, {@code 0xAARRGGBB}) into the texture; on the render thread. */
    public static void upload(DynamicTextureDeclaration declaration, int[] pixels) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("PLEXUS-CLIENT-005 dynamic textures are uploaded on the client thread");
        }
        ResourceLocation id = Views.id(declaration.id());
        DynamicTexture texture = TEXTURES.computeIfAbsent(id, key -> {
            DynamicTexture created = new DynamicTexture(declaration.width(), declaration.height(), true);
            minecraft.getTextureManager().register(key, created);
            return created;
        });
        NativeImage image = texture.getPixels();
        if (image == null) {
            throw new IllegalStateException("PLEXUS-CLIENT-005 dynamic texture " + id + " has been closed");
        }
        for (int y = 0; y < declaration.height(); y++) {
            for (int x = 0; x < declaration.width(); x++) {
                image.setPixelRGBA(x, y, abgr(pixels[y * declaration.width() + x]));
            }
        }
        texture.upload();
    }

    /**
     * 1.21.1's {@code NativeImage.setPixelRGBA} takes {@code 0xAABBGGRR} (the byte order of RGBA in memory);
     * mods give {@code 0xAARRGGBB}.
     */
    static int abgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }
}
