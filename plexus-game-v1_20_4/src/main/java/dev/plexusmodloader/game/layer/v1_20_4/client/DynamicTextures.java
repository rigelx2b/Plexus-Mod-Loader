package dev.plexusmodloader.game.layer.v1_20_4.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.plexusmodloader.game.layer.v1_20_4.Views;
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

    /** Copies {@code pixels} (rows, {@code 0xAARRGGBB}) into the texture; on the client thread. */
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
        for (int y = 0; y < declaration.height(); y++) {
            for (int x = 0; x < declaration.width(); x++) {
                int argb = pixels[y * declaration.width() + x];
                // NativeImage keeps ABGR, the declaration ARGB: swap red and blue.
                int abgr = (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF);
                image.setPixelRGBA(x, y, abgr);
            }
        }
        texture.upload();
    }
}
