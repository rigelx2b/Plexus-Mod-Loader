package dev.plexusmodloader.runtime.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quilt Standard Libraries for the player's Quilt mods (ADR-0048): on a Minecraft version where QSL has a release,
 * when a Quilt mod requires a QSL or Quilted Fabric API module that no installed jar (nested jars included) is or
 * provides, Plexus Mod Loader adds the Quilted Fabric API build it pins for the version (QSL and Fabric API's modules
 * in one jar), as if the player had put it in the mods folder. Like Fabric API ({@link FabricApiProvision}), the jar
 * is downloaded from Modrinth once, checked against the pinned SHA-512 ({@code quilted-fabric-api-releases.properties})
 * and kept in {@code .plexus/quilted-fabric-api/}; Plexus Mod Loader ships none of its files. The player sees the
 * download on the early loading window and in the log ({@code PLEXUS-DISCOVERY-006}); a failed download leaves the
 * mods as they are and says so ({@code 007}).
 *
 * <p>Runs before {@link FabricApiProvision}: Quilted Fabric API provides {@code fabric-api}, so Fabric API is then not
 * added as well. Not added: unless {@code -Dplexus.qsl=on} (off by default while QSL cannot run, ADR-0048 decision 6),
 * without a pinned release for the version (1.21.11 and 26.x), next to a host loader (QSL was not run next to Forge or
 * NeoForge), and when a jar is or provides {@code fabric-api} (both would provide it, {@code PLEXUS-COMPAT-117}).
 *
 * <p>Thread: the launch thread, once per launch.
 */
public final class QuiltedFabricApiProvision {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    /** The mod ID of Quilted Fabric API, which also names the jar that bundles QSL. */
    static final String QUILTED_FABRIC_API = "quilted_fabric_api";

    /** A pinned Quilted Fabric API build: its version, its Modrinth file and that file's SHA-512. */
    record Release(String minecraftVersion, String version, URI uri, String sha512) {

        String fileName() {
            String path = uri.getPath();
            return path.substring(path.lastIndexOf('/') + 1).replace("%2B", "+");
        }
    }

    private final Path cacheDirectory;
    private final FabricApiProvision.Downloader downloader;
    private final Consumer<String> status;
    private final Function<String, Optional<Release>> releases;

    /**
     * @param gameDirectory the game directory; the jar is kept in its {@code .plexus/quilted-fabric-api/}
     * @param downloader fetches the jar when it is not in the cache yet
     * @param status shows a line to the player while the jar is downloaded (the early loading window)
     */
    public QuiltedFabricApiProvision(
            Path gameDirectory, FabricApiProvision.Downloader downloader, Consumer<String> status) {
        this(gameDirectory, downloader, status, QuiltedFabricApiProvision::release);
    }

    /** As the public constructor, with the pinned releases from {@code releases}. */
    QuiltedFabricApiProvision(
            Path gameDirectory,
            FabricApiProvision.Downloader downloader,
            Consumer<String> status,
            Function<String, Optional<Release>> releases) {
        this.cacheDirectory = gameDirectory.resolve(".plexus").resolve("quilted-fabric-api");
        this.downloader = downloader;
        this.status = status;
        this.releases = releases;
    }

    /**
     * {@code files} with the pinned Quilted Fabric API added when a Quilt mod needs a QSL module none of them has;
     * {@code files} itself otherwise, and when the jar cannot be had (reported, not thrown).
     */
    public List<DiscoveredModFile> provide(
            List<DiscoveredModFile> files,
            String gameVersion,
            FabricApiProvision.Host host,
            boolean developmentLaunch) {
        if (!FabricApiProvision.enabled(System.getProperty("plexus.qsl", "off"), developmentLaunch)) {
            return files;
        }
        Set<String> present = new HashSet<>();
        Set<String> needed = new TreeSet<>();
        List<Requirement> requirements = new ArrayList<>();
        for (DiscoveredModFile file : files) {
            if (file.isFor(ModFileKind.FABRIC) || file.isFor(ModFileKind.QUILT)) {
                read(file.path(), present, requirements);
            }
        }
        for (Requirement requirement : requirements) {
            if (isQslModule(requirement.id())
                    && !present.contains(requirement.id())
                    && (requirement.unless() == null || !present.contains(requirement.unless()))) {
                needed.add(requirement.id());
            }
        }
        if (needed.isEmpty() || present.contains(QUILTED_FABRIC_API) || present.contains("qsl")) {
            return files;
        }
        Optional<Release> release = releases.apply(gameVersion);
        if (release.isEmpty()) {
            return files;
        }
        if (host != FabricApiProvision.Host.NONE) {
            LOGGER.info(
                    "[Plexus Mod Loader] PLEXUS-DISCOVERY-006 Quilted Fabric API (which brings the Quilt Standard Libraries) is not added automatically for the installed Quilt mods because it has not been run next to {}; the mods need {}",
                    host == FabricApiProvision.Host.FORGE ? "MinecraftForge" : "NeoForge",
                    needed);
            return files;
        }
        if (present.contains(FabricApiProvision.FABRIC_API)) {
            LOGGER.warn(
                    "[Plexus Mod Loader] PLEXUS-DISCOVERY-006 Quilted Fabric API is not added automatically for the Quilt mods that need {} because Fabric API is installed and both provide the mod ID fabric-api. Replace Fabric API in the mods folder with Quilted Fabric API for Minecraft {}.",
                    needed,
                    gameVersion);
            return files;
        }
        Optional<Path> jar = ensure(release.get());
        if (jar.isEmpty()) {
            return files;
        }
        LOGGER.info(
                "[Plexus Mod Loader] PLEXUS-DISCOVERY-006 Quilted Fabric API {} is added automatically from {} because installed Quilt mods need {}. Putting your own Quilted Fabric API in the mods folder makes Plexus use that one instead, and -Dplexus.qsl=off turns this off.",
                release.get().version(),
                jar.get(),
                needed);
        List<DiscoveredModFile> withQsl = new ArrayList<>(files);
        withQsl.add(new DiscoveredModFile(jar.get(), ModFileKind.QUILT, Optional.empty()));
        return List.copyOf(withQsl);
    }

    /**
     * Whether {@code id} names a module of QSL or Quilted Fabric API: {@code qsl}, {@code quilted_fabric_api}, its
     * {@code quilted_fabric_*} modules and QSL's {@code quilt_*} modules ({@code quilt_loader} is Quilt Loader itself).
     * A mod's own {@code quilt_*} library does not trigger a download, because it is installed (nested or not) and so
     * counts as present.
     */
    static boolean isQslModule(String id) {
        return id.equals("qsl")
                || id.startsWith("quilted_fabric_")
                || (id.startsWith("quilt_") && !id.equals("quilt_loader"));
    }

    /** One required dependency of a Quilt mod: its ID and the ID that waives it ({@code unless}), if any. */
    record Requirement(String id, String unless) {}

    /**
     * Adds the IDs the jar and its nested jars ({@code META-INF/jars/}, one level) are or provide to {@code present},
     * and the required dependencies of its {@code quilt.mod.json} to {@code requirements}. A jar that cannot be read
     * adds nothing; the scanner reports it.
     */
    static void read(Path jar, Set<String> present, List<Requirement> requirements) {
        try (JarFile file = new JarFile(jar.toFile())) {
            present.addAll(FabricApiProvision.modIds(file));
            ZipEntry quilt = file.getEntry(ModFileKind.QUILT.metadataPath());
            if (quilt != null && file.getEntry(ModFileKind.FABRIC.metadataPath()) == null) {
                JsonObject loader = json(file.getInputStream(quilt)).getAsJsonObject("quilt_loader");
                if (loader != null && loader.get("depends") instanceof JsonArray depends) {
                    for (JsonElement dependency : depends) {
                        addRequirement(dependency, requirements);
                    }
                }
            }
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/jars/")
                        && entry.getName().endsWith(".jar")) {
                    present.addAll(nestedIds(file.getInputStream(entry)));
                }
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable metadata: the scanner and the bridges report the jar themselves.
        }
    }

    private static void addRequirement(JsonElement dependency, List<Requirement> requirements) {
        if (dependency.isJsonPrimitive()) {
            requirements.add(new Requirement(withoutGroup(dependency.getAsString()), null));
        } else if (dependency.isJsonObject()) {
            JsonObject object = dependency.getAsJsonObject();
            boolean optional = object.has("optional") && object.get("optional").getAsBoolean();
            if (!optional && object.has("id")) {
                String unless =
                        object.get("unless") != null && object.get("unless").isJsonPrimitive()
                                ? withoutGroup(object.get("unless").getAsString())
                                : null;
                requirements.add(new Requirement(withoutGroup(object.get("id").getAsString()), unless));
            }
        }
        // An array is "any of": met by whichever alternative is installed, so it does not ask for QSL by itself.
    }

    /** {@code group:id} as {@code id}, the part the dependency resolution compares. */
    private static String withoutGroup(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    /** The IDs a nested jar's {@code fabric.mod.json} and {@code quilt.mod.json} declare and provide. */
    private static Set<String> nestedIds(InputStream nested) throws IOException {
        Set<String> ids = new HashSet<>();
        try (JarInputStream in = new JarInputStream(nested)) {
            for (JarEntry entry = in.getNextJarEntry(); entry != null; entry = in.getNextJarEntry()) {
                if (entry.getName().equals(ModFileKind.FABRIC.metadataPath())) {
                    JsonObject json = json(in);
                    addString(ids, json.get("id"));
                    addProvides(ids, json.get("provides"));
                } else if (entry.getName().equals(ModFileKind.QUILT.metadataPath())) {
                    JsonObject loader = json(in).getAsJsonObject("quilt_loader");
                    if (loader != null) {
                        addString(ids, loader.get("id"));
                        addProvides(ids, loader.get("provides"));
                    }
                }
            }
        }
        return ids;
    }

    private static JsonObject json(InputStream input) throws IOException {
        return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void addProvides(Set<String> ids, JsonElement provides) {
        if (provides instanceof JsonArray array) {
            for (JsonElement element : array) {
                addString(
                        ids, element.isJsonObject() ? element.getAsJsonObject().get("id") : element);
            }
        }
    }

    private static void addString(Set<String> ids, JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            ids.add(withoutGroup(element.getAsString()));
        }
    }

    /** The jar in the cache, downloaded first when it is missing or damaged; empty when it cannot be had. */
    Optional<Path> ensure(Release release) {
        Path target = cacheDirectory.resolve(release.version()).resolve(release.fileName());
        try {
            if (Files.isRegularFile(target) && sha512(target).equalsIgnoreCase(release.sha512())) {
                return Optional.of(target);
            }
            status.accept(String.format(Locale.ROOT, "Downloading Quilted Fabric API %s", release.version()));
            LOGGER.info(
                    "[Plexus Mod Loader] PLEXUS-DISCOVERY-006 Downloading Quilted Fabric API {} from {} for installed Quilt mods; later launches reuse the downloaded file.",
                    release.version(),
                    release.uri());
            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + ".partial");
            Files.deleteIfExists(partial);
            downloader.download(release.uri(), partial);
            String actual = sha512(partial);
            if (!actual.equalsIgnoreCase(release.sha512())) {
                Files.deleteIfExists(partial);
                throw new IOException("SHA-512 " + actual + " of " + release.uri() + ", expected " + release.sha512());
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Optional.of(target);
        } catch (IOException e) {
            LOGGER.warn(
                    "[Plexus Mod Loader] PLEXUS-DISCOVERY-007 Failed to download Quilted Fabric API {} ({}), so Quilt mods that need the Quilt Standard Libraries are not loaded. Connect to the internet and start again, or download Quilted Fabric API for this Minecraft version from Modrinth and put it in the mods folder.",
                    release.version(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    /** The pinned release for {@code gameVersion}, from {@code quilted-fabric-api-releases.properties}. */
    static Optional<Release> release(String gameVersion) {
        Properties releases = new Properties();
        try (InputStream input =
                QuiltedFabricApiProvision.class.getResourceAsStream("quilted-fabric-api-releases.properties")) {
            if (input == null) {
                return Optional.empty();
            }
            releases.load(input);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read quilted-fabric-api-releases.properties", e);
        }
        String version = releases.getProperty(gameVersion + ".version");
        if (version == null) {
            return Optional.empty();
        }
        return Optional.of(new Release(
                gameVersion,
                version,
                URI.create(releases.getProperty(gameVersion + ".url")),
                releases.getProperty(gameVersion + ".sha512")));
    }

    private static String sha512(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int read = input.read(buffer); read > 0; read = input.read(buffer)) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is not available", e);
        }
    }
}
