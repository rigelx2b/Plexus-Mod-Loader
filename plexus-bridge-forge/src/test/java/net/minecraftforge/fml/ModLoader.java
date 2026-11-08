package net.minecraftforge.fml;

/** Test stand-in for FML's {@code ModLoader}: only {@code isLoadingStateValid}, set by the test. */
public final class ModLoader {

    public static volatile boolean valid = true;

    private ModLoader() {}

    public static boolean isLoadingStateValid() {
        return valid;
    }
}
