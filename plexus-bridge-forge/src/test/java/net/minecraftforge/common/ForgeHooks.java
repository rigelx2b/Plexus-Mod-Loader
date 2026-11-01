package net.minecraftforge.common;

import net.minecraft.resources.Identifier;

/** Test stand-in for Forge's {@code ForgeHooks}: only {@code getCustomPayloadCodec}, naming the codec it gives. */
public final class ForgeHooks {

    private ForgeHooks() {}

    public static Object getCustomPayloadCodec(Identifier id, int maxSize) {
        return "Forge's codec for " + id.text();
    }
}
