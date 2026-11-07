package dev.plexusmodloader.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The cache key does not depend on whether the SHA-1 values were computed or taken from Mojang's version data. */
class RemappedGameJarKeyTest {

    @TempDir
    Path directory;

    @Test
    void theKeyFromListedValuesIsTheKeyFromTheFiles() throws Exception {
        Path jar = Files.write(directory.resolve("client.jar"), "jar bytes".getBytes(StandardCharsets.UTF_8));
        Path mappings = Files.write(directory.resolve("mappings.txt"), "a -> b:".getBytes(StandardCharsets.UTF_8));
        String fromFiles = RemappedGameJar.key(jar, mappings);

        // Mojang lists lower-case values; a list in upper case must give the same key (and so the same cache file).
        JsonObject listedJar = listed(VerifiedDownloads.sha1(jar).toUpperCase(java.util.Locale.ROOT));
        JsonObject listedMappings = listed(VerifiedDownloads.sha1(mappings));
        assertEquals(
                fromFiles,
                RemappedGameJar.key(ClientGameRemap.listedSha1(listedJar), ClientGameRemap.listedSha1(listedMappings)));
    }

    @Test
    void theKeyKeepsItsThreeLineFormat() {
        String key = RemappedGameJar.key("aa", "bb");
        assertEquals("jar=aa\nmappings=bb\nremapper=" + MojangJarRemapper.REVISION + "\n", key);
    }

    private static JsonObject listed(String sha1) {
        JsonObject download = new JsonObject();
        download.addProperty("sha1", sha1);
        return download;
    }
}
