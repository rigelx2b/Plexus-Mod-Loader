package dev.plexusmodloader.game.version.layer;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * The empty structure file of a game test area, written without the game's classes. Game layers write their generated
 * data pack while they start, before a bridge's Mixin is ready; loading the game's NBT classes or {@code
 * SharedConstants} then would define hundreds of game classes (down to {@code UUIDUtil} through {@code
 * Component.translatable}) before mods could change them, and a Fabric or Quilt mod's Mixin into any of them would stop
 * the game ("target ... was loaded too early", LAN Properties on 1.20.4, 2026-11-09).
 *
 * <p>The file is the gzip-compressed named binary tag the game's {@code NbtIo.writeCompressed} writes for a structure
 * with no blocks, palette or entities: an unnamed root compound with {@code size} (a list of the three ints), empty
 * {@code blocks}, {@code palette} and {@code entities} lists and {@code DataVersion}. The game layer tests read it back
 * with the game's {@code NbtIo} on every supported version.
 *
 * <p>Thread: stateless; any thread.
 */
@ApiStability(StabilityLevel.INTERNAL)
public final class EmptyStructureFile {

    private static final byte TAG_END = 0;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;
    private static final Pattern WORLD_VERSION = Pattern.compile("\"world_version\"\\s*:\\s*(\\d+)");

    private EmptyStructureFile() {}

    /** Writes the structure to {@code file} (replaced if it exists); the parent directory must exist. */
    public static void write(Path file, int width, int height, int depth, int dataVersion) throws IOException {
        try (OutputStream raw = Files.newOutputStream(file);
                DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("");
            out.writeByte(TAG_LIST);
            out.writeUTF("size");
            out.writeByte(TAG_INT);
            out.writeInt(3);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(depth);
            for (String empty : new String[] {"blocks", "palette", "entities"}) {
                out.writeByte(TAG_LIST);
                out.writeUTF(empty);
                out.writeByte(TAG_END);
                out.writeInt(0);
            }
            out.writeByte(TAG_INT);
            out.writeUTF("DataVersion");
            out.writeInt(dataVersion);
            out.writeByte(TAG_END);
        }
    }

    /**
     * The data version of the game {@code gameClassLoader} loads: {@code world_version} of the game jar's {@code
     * version.json}, the number {@code SharedConstants.getCurrentVersion()} reports.
     *
     * @throws IOException when the loader has no {@code version.json} or it names no {@code world_version}
     */
    public static int dataVersion(ClassLoader gameClassLoader) throws IOException {
        try (InputStream in = gameClassLoader.getResourceAsStream("version.json")) {
            if (in == null) {
                throw new IOException("the game's version.json is not on " + gameClassLoader);
            }
            Matcher matcher = WORLD_VERSION.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!matcher.find()) {
                throw new IOException("the game's version.json has no world_version");
            }
            return Integer.parseInt(matcher.group(1));
        }
    }
}
