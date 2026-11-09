package dev.plexusmodloader.game.layer.v26_2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.plexusmodloader.api.worldgen.GenerationStepName;
import dev.plexusmodloader.game.version.layer.EmptyStructureFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.levelgen.GenerationStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The generated data pack's parts that the layer writes without the game's classes, read back with the game's. */
class EmptyStructureFileTest {

    @Test
    void theGameReadsTheEmptyStructureAsTheStructureItWouldWrite(@TempDir Path temp) throws IOException {
        SharedConstants.tryDetectVersion();
        int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        Path file = temp.resolve("empty_3x4x5.nbt");

        EmptyStructureFile.write(file, 3, 4, 5, dataVersion);

        CompoundTag expected = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(3));
        size.add(IntTag.valueOf(4));
        size.add(IntTag.valueOf(5));
        expected.put("size", size);
        expected.put("blocks", new ListTag());
        expected.put("palette", new ListTag());
        expected.put("entities", new ListTag());
        expected.putInt("DataVersion", dataVersion);
        assertEquals(expected, NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap()));
    }

    @Test
    void theDataVersionIsTheGamesOwn() throws IOException {
        SharedConstants.tryDetectVersion();
        assertEquals(
                SharedConstants.getCurrentVersion().dataVersion().version(),
                EmptyStructureFile.dataVersion(getClass().getClassLoader()));
    }

    @Test
    void theApisGenerationStepsAreTheGamesInOrder() {
        assertEquals(
                Arrays.stream(GenerationStep.Decoration.values())
                        .map(Enum::name)
                        .toList(),
                Arrays.stream(GenerationStepName.values()).map(Enum::name).toList());
    }

    /**
     * The layer writes its data pack while it starts, before a bridge's Mixin is ready; the writer names none of the
     * game classes it used to load then (SharedConstants, the NBT classes, GenerationStep), which defined hundreds of
     * game classes too early for other loaders' mods to change.
     */
    @Test
    void theDataPackWriterNamesNoGameClassItWouldLoadEarly() throws IOException {
        byte[] bytes;
        try (var in = GeneratedDataPack.class.getResourceAsStream("GeneratedDataPack.class")) {
            bytes = in.readAllBytes();
        }
        var named = java.lang.classfile.ClassFile.of().parse(bytes).constantPool();
        java.util.List<String> early = new java.util.ArrayList<>();
        for (var entry : named) {
            if (entry instanceof java.lang.classfile.constantpool.ClassEntry type) {
                String name = type.asInternalName();
                if (name.equals("net/minecraft/SharedConstants")
                        || name.startsWith("net/minecraft/nbt/")
                        || name.startsWith("net/minecraft/world/level/levelgen/GenerationStep")) {
                    early.add(name);
                }
            }
        }
        assertEquals(java.util.List.of(), early);
    }
}
