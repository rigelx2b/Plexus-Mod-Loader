package dev.plexusmodloader.game.layer.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.data.DataTree;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.world.StorageOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * World storage is the game's saved data: what a mod puts in a {@link DataTree} is written to the world's {@code
 * data/} folder and read back by a new storage object, as when a world is closed and opened again.
 */
class WorldStoragePersistenceTest {

    private static final ResourceId ID = ResourceId.of("demo", "stats/counters");

    @BeforeAll
    static void bootstrap() {
        LayerTestSupport.bootstrap();
    }

    private static WorldServices.ModSavedData open(DimensionDataStorage storage, StorageOptions options) {
        return storage.computeIfAbsent(
                tag -> new WorldServices.ModSavedData(WorldServices.load(tag, ID, options), ID),
                () -> new WorldServices.ModSavedData(DataTree.create(), ID),
                WorldServices.dataFileName(ID));
    }

    @Test
    void storedDataIsWrittenToTheWorldAndReadBackByANewStorage(@TempDir Path world) throws IOException {
        StorageOptions options = StorageOptions.DEFAULT;
        String file = WorldServices.dataFileName(ID);

        DimensionDataStorage first = new DimensionDataStorage(world.toFile(), DataFixers.getDataFixer());
        WorldServices.ModSavedData saved = open(first, options);
        saved.tree.putInt("kills", 7);
        saved.tree.child("nested").putString("name", "ruby");
        saved.setDirty();
        first.save();

        assertTrue(Files.isRegularFile(world.resolve(file + ".dat")), "the storage is a file of the world: " + file);

        DimensionDataStorage second = new DimensionDataStorage(world.toFile(), DataFixers.getDataFixer());
        WorldServices.ModSavedData loaded = open(second, options);
        assertEquals(7, loaded.tree.getInt("kills", 0));
        assertEquals("ruby", loaded.tree.child("nested").getString("name", ""));
    }

    @Test
    void unchangedStorageIsNotWritten(@TempDir Path world) {
        DimensionDataStorage storage = new DimensionDataStorage(world.toFile(), DataFixers.getDataFixer());
        open(storage, StorageOptions.DEFAULT);
        storage.save();
        assertTrue(
                !Files.exists(world.resolve(WorldServices.dataFileName(ID) + ".dat")),
                "only dirty saved data is written");
    }

    @Test
    void theFileNameIsFlatAndPerStorage() {
        assertEquals("plexus_demo__stats__counters", WorldServices.dataFileName(ID));
    }

    @Test
    void theVersionIsStoredBesideTheData() {
        StorageOptions options = StorageOptions.DEFAULT.withVersion(3, (data, from) -> {});
        DataTree tree = DataTree.create();
        tree.putString("a", "b");
        CompoundTag tag = WorldServices.save(tree, options);
        assertEquals(3, tag.getInt(WorldServices.VERSION_KEY));
        assertEquals("b", tag.getString("a"));
    }
}
