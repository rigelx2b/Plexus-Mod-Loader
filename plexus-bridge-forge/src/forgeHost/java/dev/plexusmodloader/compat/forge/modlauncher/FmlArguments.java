package dev.plexusmodloader.compat.forge.modlauncher;

import java.util.ArrayList;
import java.util.List;

/** The launch arguments FML 4 reads itself and the game must not get, as ModLauncher leaves service options out. */
public final class FmlArguments {

    private FmlArguments() {}

    /** {@code args} without the {@code --fml.*} options and their values; everything else in order. */
    public static String[] withoutFml(String[] args) {
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--fml.")) {
                i++;
            } else {
                kept.add(args[i]);
            }
        }
        return kept.toArray(String[]::new);
    }
}
