package dev.plexusmodloader.compat.fabric.intermediary;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Small mappings with the shapes the real ones have, for the tests of this package. */
final class TestMappings {

    /**
     * Official (obfuscated) names {@code a}, {@code b}, {@code c}. {@code method_20} with the same descriptor is in
     * {@code a} and in {@code b} and means different Mojang methods there.
     */
    static final String TINY = String.join(
            "\n",
            "tiny\t2\t0\tofficial\tintermediary",
            "c\ta\tnet/minecraft/class_1",
            "\tf\tI\ta\tfield_10",
            "\tf\tLb;\tb\tfield_11",
            "\tm\t(La;)V\ta\tmethod_20",
            "\tm\t()I\tb\tmethod_21",
            "\tm\t(I)V\tc\tmethod_22",
            "c\tb\tnet/minecraft/class_2",
            "\tm\t(La;)V\ta\tmethod_20",
            "c\tb$a\tnet/minecraft/class_2$class_3",
            "c\tc\tnet/minecraft/class_4",
            "c\tcom/mojang/Kept\tcom/mojang/Kept",
            "");

    static final String PROGUARD = String.join(
            "\n",
            "# test mappings",
            "net.minecraft.world.Level -> a:",
            "    int count -> a",
            "    net.minecraft.server.Other other -> b",
            "    1:1:void tick(net.minecraft.world.Level) -> a",
            "    int size() -> b",
            "    void setCount(int) -> c",
            "net.minecraft.server.Other -> b:",
            "    void run(net.minecraft.world.Level) -> a",
            "net.minecraft.server.Other$Inner -> b$a:",
            "net.minecraft.world.Dimension -> c:",
            "");

    private TestMappings() {}

    static IntermediaryTranslation translation() {
        return IntermediaryTranslation.of(tiny(), proguard());
    }

    static IntermediaryMappings tiny() {
        try {
            return IntermediaryMappings.parse(new ByteArrayInputStream(TINY.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static MojangNames proguard() {
        return MojangNames.parse(List.of(PROGUARD.split("\n")));
    }
}
