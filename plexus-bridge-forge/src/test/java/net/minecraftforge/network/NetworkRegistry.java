package net.minecraftforge.network;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** Test stand-in for Forge's {@code NetworkRegistry}: only {@code findTarget}, over the channels a test puts in. */
public final class NetworkRegistry {

    private static final Map<Identifier, Object> CHANNELS = new HashMap<>();

    private NetworkRegistry() {}

    public static Map<Identifier, Object> channels() {
        return CHANNELS;
    }

    public static Object findTarget(Identifier id) {
        return CHANNELS.get(id);
    }
}
