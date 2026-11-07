package dev.plexusmodloader.runtime.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric API for the player's Fabric mods (ADR-0046): when the mods folder has Fabric or Quilt mods and no jar that is
 * or provides {@code fabric-api}, Plexus Mod Loader adds the Fabric API release it pins for the Minecraft version, as if
 * the player had put it in the mods folder. The jar is downloaded from Modrinth once, checked against the SHA-512 Plexus
 * Mod Loader pins ({@code fabric-api-releases.properties}), and kept in {@code .plexus/fabric-api/} of the game
 * directory; later launches only check the hash. The player sees the download on the early loading window and in the
 * log ({@code PLEXUS-DISCOVERY-004}); a failed download leaves the mods as they are and says so ({@code 005}). Plexus
 * Mod Loader ships none of Fabric API's files.
 *
 * <p>Not added: with {@code -Dplexus.fabricApi=off}, in a development launch (mods from {@code -Dplexus.devModJars},
 * such as the Gradle runs, unless {@code -Dplexus.fabricApi=on}), without a pinned release for the version, and where
 * Fabric API cannot run next to the host loader of the launch yet (Forge on 1.20.1 and 1.20.4, NeoForge on 1.20.4 and
 * 1.21.1).
 *
 * <p>Thread: the launch thread, once per launch.
 */
public final class FabricApiProvision {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    /** The mod ID of Fabric API, which the jar declares and Quilted Fabric API provides. */
    static final String FABRIC_API = "fabric-api";

    /** The host loader the launch runs, if any; Fabric API does not run next to some of them yet. */
    public enum Host {
        NONE,
        FORGE,
        NEOFORGE
    }

    /** A pinned Fabric API build: its version, its Modrinth file and that file's SHA-512. */
    record Release(String minecraftVersion, String version, URI uri, String sha512) {

        String fileName() {
            String path = uri.getPath();
            return path.substring(path.lastIndexOf('/') + 1).replace("%2B", "+");
        }
    }

    /** Downloads one file; tests replace it. */
    @FunctionalInterface
    public interface Downloader {
        void download(URI uri, Path target) throws IOException;
    }

    private final Path cacheDirectory;
    private final Downloader downloader;
    private final Consumer<String> status;
    private final Function<String, Optional<Release>> releases;

    /**
     * @param gameDirectory the game directory; the jar is kept in its {@code .plexus/fabric-api/}
     * @param downloader fetches the jar when it is not in the cache yet
     * @param status shows a line to the player while the jar is downloaded (the early loading window)
     */
    public FabricApiProvision(Path gameDirectory, Downloader downloader, Consumer<String> status) {
        this(gameDirectory, downloader, status, FabricApiProvision::release);
    }

    /** As {@link #FabricApiProvision(Path, Downloader, Consumer)}, with the pinned releases from {@code releases}. */
    FabricApiProvision(
            Path gameDirectory,
            Downloader downloader,
            Consumer<String> status,
            Function<String, Optional<Release>> releases) {
        this.cacheDirectory = gameDirectory.resolve(".plexus").resolve("fabric-api");
        this.downloader = downloader;
        this.status = status;
        this.releases = releases;
    }

    /**
     * {@code files} with the pinned Fabric API added when the mods need it and none of them is Fabric API; {@code files}
     * itself otherwise, and when the jar cannot be had (reported, not thrown).
     */
    public List<DiscoveredModFile> provide(
            List<DiscoveredModFile> files, String gameVersion, Host host, boolean developmentLaunch) {
        if (!enabled(System.getProperty("plexus.fabricApi", "auto"), developmentLaunch)) {
            return files;
        }
        boolean fabricMods =
                files.stream().anyMatch(file -> file.isFor(ModFileKind.FABRIC) || file.isFor(ModFileKind.QUILT));
        if (!fabricMods || files.stream().anyMatch(FabricApiProvision::isFabricApi)) {
            return files;
        }
        if (!runsNextTo(host, gameVersion)) {
            LOGGER.info(
                    "[Plexus Mod Loader] PLEXUS-DISCOVERY-004 Fabric API is not added automatically for installed Fabric mods because it cannot run next to {} on Minecraft {} yet.",
                    host == Host.FORGE ? "MinecraftForge" : "NeoForge",
                    gameVersion);
            return files;
        }
        Optional<Release> release = releases.apply(gameVersion);
        if (release.isEmpty()) {
            return files;
        }
        Optional<Path> jar = ensure(release.get());
        if (jar.isEmpty()) {
            return files;
        }
        LOGGER.info(
                "[Plexus Mod Loader] PLEXUS-DISCOVERY-004 Fabric API {} is added automatically from {}. Putting your own Fabric API in the mods folder makes Plexus use that one instead, and -Dplexus.fabricApi=off turns this off.",
                release.get().version(),
                jar.get());
        List<DiscoveredModFile> withFabricApi = new ArrayList<>(files);
        withFabricApi.add(new DiscoveredModFile(jar.get(), ModFileKind.FABRIC, Optional.empty()));
        return List.copyOf(withFabricApi);
    }

    /** Whether the setting {@code plexus.fabricApi} ({@code auto}, {@code on}, {@code off}) adds Fabric API. */
    static boolean enabled(String setting, boolean developmentLaunch) {
        return switch (setting) {
            case "on" -> true;
            case "off" -> false;
            default -> !developmentLaunch;
        };
    }

    /** Whether Fabric API can run next to {@code host} on {@code gameVersion} (docs/COMPATIBILITY.md). */
    static boolean runsNextTo(Host host, String gameVersion) {
        return switch (host) {
            case NONE -> true;
            case FORGE -> !gameVersion.equals("1.20.1") && !gameVersion.equals("1.20.4");
            case NEOFORGE -> !gameVersion.equals("1.20.4") && !gameVersion.equals("1.21.1");
        };
    }

    /** The jar in the cache, downloaded first when it is missing or damaged; empty when it cannot be had. */
    Optional<Path> ensure(Release release) {
        Path target = cacheDirectory.resolve(release.version()).resolve(release.fileName());
        try {
            if (Files.isRegularFile(target) && sha512(target).equalsIgnoreCase(release.sha512())) {
                return Optional.of(target);
            }
            status.accept(String.format(java.util.Locale.ROOT, "Downloading Fabric API %s", release.version()));
            LOGGER.info(
                    "[Plexus Mod Loader] PLEXUS-DISCOVERY-004 Downloading Fabric API {} from {} for installed Fabric mods; later launches reuse the downloaded file.",
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
                    "[Plexus Mod Loader] PLEXUS-DISCOVERY-005 Failed to download Fabric API {} ({}), so Fabric mods that need Fabric API are not loaded. Connect to the internet and start again, or download Fabric API for this Minecraft version from Modrinth and put it in the mods folder.",
                    release.version(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    /** The pinned release for {@code gameVersion}, from {@code fabric-api-releases.properties}. */
    static Optional<Release> release(String gameVersion) {
        Properties releases = new Properties();
        try (InputStream input = FabricApiProvision.class.getResourceAsStream("fabric-api-releases.properties")) {
            if (input == null) {
                return Optional.empty();
            }
            releases.load(input);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fabric-api-releases.properties", e);
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

    /** Whether {@code file} is Fabric API, or provides it (Quilted Fabric API), by its own metadata. */
    static boolean isFabricApi(DiscoveredModFile file) {
        if (!file.isFor(ModFileKind.FABRIC) && !file.isFor(ModFileKind.QUILT)) {
            return false;
        }
        try (JarFile jar = new JarFile(file.path().toFile())) {
            return modIds(jar).contains(FABRIC_API);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The IDs a jar's {@code fabric.mod.json} and {@code quilt.mod.json} declare and provide. */
    static Set<String> modIds(JarFile jar) throws IOException {
        Set<String> ids = new HashSet<>();
        ZipEntry fabric = jar.getEntry(ModFileKind.FABRIC.metadataPath());
        if (fabric != null) {
            JsonObject json = read(jar, fabric);
            addString(ids, json.get("id"));
            addAll(ids, json.get("provides"));
        }
        ZipEntry quilt = jar.getEntry(ModFileKind.QUILT.metadataPath());
        if (quilt != null) {
            JsonObject loader = read(jar, quilt).getAsJsonObject("quilt_loader");
            if (loader != null) {
                addString(ids, loader.get("id"));
                addAll(ids, loader.get("provides"));
            }
        }
        return ids;
    }

    private static JsonObject read(JarFile jar, ZipEntry entry) throws IOException {
        try (InputStream input = jar.getInputStream(entry)) {
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static void addAll(Set<String> ids, JsonElement provides) {
        if (provides instanceof JsonArray array) {
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    addString(ids, element.getAsJsonObject().get("id"));
                } else {
                    addString(ids, element);
                }
            }
        }
    }

    private static void addString(Set<String> ids, JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            ids.add(element.getAsString());
        }
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

    /** The default downloader: HTTPS through the JDK's client, following redirects. */
    public static Downloader httpDownloader() {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return (uri, target) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "Plexus Mod Loader")
                    .build();
            try {
                HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
                if (response.statusCode() != 200) {
                    Files.deleteIfExists(target);
                    throw new IOException("HTTP " + response.statusCode() + " for " + uri);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while downloading " + uri, e);
            }
        };
    }
}
