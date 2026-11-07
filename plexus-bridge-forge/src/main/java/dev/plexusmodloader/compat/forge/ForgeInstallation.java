package dev.plexusmodloader.compat.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.plexusmodloader.api.runtime.GameSide;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Forge's files for one Minecraft version, put together on the player's PC the way Forge's installer does (ADR-0021
 * G3c): the installer from Forge's Maven (checked against the SHA-1 Plexus Mod Loader pins), the runtime libraries its
 * {@code version.json} lists (each checked against the SHA-1 it gives), and the game jar with Forge's binary patches
 * applied (G2). Everything is cached in one folder per Forge version; a later launch only checks hashes. Plexus Mod Loader
 * itself ships none of these files.
 *
 * <p>Thread: one launch prepares at a time; the methods are not safe for concurrent use on the same folder.
 */
final class ForgeInstallation {

    static final String MAVEN = "https://maven.minecraftforge.net/";

    /** The Forge release Plexus Mod Loader uses for a Minecraft version ({@code forge-releases.properties}). */
    record Release(String minecraftVersion, String forgeVersion, String installerSha1) {

        URI installerUri() {
            return URI.create(
                    MAVEN + "net/minecraftforge/forge/" + forgeVersion + "/forge-" + forgeVersion + "-installer.jar");
        }
    }

    /**
     * The prepared files.
     *
     * @param patchedGame the game jar with Forge's patches; the game layer's {@code minecraft} module
     * @param libraries Forge's runtime libraries in {@code version.json} order, Forge's {@code universal.jar} among
     *     them
     * @param fileSystemJars the four of them that must be on the JVM class path ({@link ForgeFileSystems})
     * @param srgNames for Forge 1.20.x, whose runtime names Minecraft's fields and methods in SRG: the table that renames
     *     them to Mojang's ({@link SrgMojangNames}); {@code patchedGame} and the Forge libraries are already renamed.
     *     Empty for Forge versions that run on Mojang names
     * @param fmlArguments the {@code --fml.*} program arguments Forge's launcher profile gives ({@code --fml.forgeVersion
     *     47.4.28} ...), in pairs; Forge 47 (Minecraft 1.20.1) refuses to start without them
     */
    record Prepared(
            Release release,
            Path directory,
            Path patchedGame,
            List<Path> libraries,
            List<Path> fileSystemJars,
            Optional<SrgMojangNames> srgNames,
            List<String> fmlArguments) {}

    /** Downloads one file; tests replace it. */
    @FunctionalInterface
    interface Downloader {
        void download(URI uri, Path target) throws IOException;
    }

    private ForgeInstallation() {}

    /** The Forge release for {@code minecraftVersion}, or empty when Plexus Mod Loader has none for it. */
    static Optional<Release> release(String minecraftVersion) {
        Properties releases = new Properties();
        try (InputStream in = ForgeInstallation.class.getResourceAsStream("forge-releases.properties")) {
            if (in == null) {
                return Optional.empty();
            }
            releases.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read forge-releases.properties", e);
        }
        String value = releases.getProperty(minecraftVersion);
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.strip().split("\\s+");
        return Optional.of(new Release(minecraftVersion, parts[0], parts[1]));
    }

    /**
     * Downloads what is missing or damaged and builds the patched game jar when the vanilla jar changed.
     *
     * @param cacheRoot the folder that holds one folder per Forge version
     * @param side which game jar {@code vanillaGame} is; Forge has patches for each
     * @param vanillaGame the game jar the patches apply to (the dedicated server's classes, or the client jar)
     * @param log receives one line per step
     * @throws IOException when a download fails, a file does not have the expected SHA-1, or a patch does not apply
     */
    static Prepared prepare(
            Release release,
            Path cacheRoot,
            GameSide side,
            Path vanillaGame,
            Downloader downloader,
            Consumer<String> log)
            throws IOException {
        Path directory = cacheRoot.resolve(release.forgeVersion());
        Files.createDirectories(directory);
        Path installer = directory.resolve("forge-" + release.forgeVersion() + "-installer.jar");
        ensure(installer, release.installerSha1(), file -> downloader.download(release.installerUri(), file), log);

        List<Path> libraries = new ArrayList<>();
        String sideName = side == GameSide.CLIENT ? "client" : "server";
        Optional<SrgMojangNames> srgNames;
        List<String> fmlArguments;
        Path patched = directory.resolve("forge-" + release.forgeVersion() + "-" + sideName + ".jar");
        List<Path> fileSystemJars;
        try (JarFile jar = new JarFile(installer.toFile())) {
            JsonObject version = json(jar, "version.json");
            fileSystemJars = fileSystemJars(version, directory.resolve("libraries"));
            fmlArguments = fmlArguments(version);
            for (JsonElement element : version.getAsJsonArray("libraries")) {
                JsonObject library = element.getAsJsonObject();
                String name = library.get("name").getAsString();
                if (name.startsWith("net.minecraftforge:forge:")
                        && (name.endsWith(":client") || name.endsWith(":server"))) {
                    // The patched game jar, which Plexus Mod Loader builds itself below.
                    continue;
                }
                JsonObject artifact = library.getAsJsonObject("downloads").getAsJsonObject("artifact");
                String path = artifact.get("path").getAsString();
                String sha1 = artifact.get("sha1").getAsString();
                String url = artifact.has("url") ? artifact.get("url").getAsString() : "";
                Path target = directory.resolve("libraries").resolve(path).normalize();
                if (!target.startsWith(directory.resolve("libraries"))) {
                    throw new IOException("Forge library path " + path + " leaves the libraries folder");
                }
                ZipEntry bundled = jar.getEntry("maven/" + path);
                if (url.isEmpty() && bundled == null) {
                    throw new IOException("Forge's installer names " + name + " but has neither a URL nor a copy");
                }
                ensure(
                        target,
                        sha1,
                        bundled != null
                                ? file -> copy(jar, bundled, file)
                                : file -> downloader.download(URI.create(url), file),
                        log);
                libraries.add(target);
            }
            // Forge 47 (Minecraft 1.20.1) lists FML's own jars and the universal jar only in install_profile.json,
            // bundled in
            // the installer; later builds list them in version.json too (added above).
            JsonObject installProfile = json(jar, "install_profile.json");
            if (installProfile.has("libraries")) {
                for (JsonElement element : installProfile.getAsJsonArray("libraries")) {
                    JsonObject library = element.getAsJsonObject();
                    String name = library.get("name").getAsString();
                    if (!name.startsWith("net.minecraftforge:") || !library.has("downloads")) {
                        continue;
                    }
                    JsonObject artifact = library.getAsJsonObject("downloads").getAsJsonObject("artifact");
                    String path = artifact.get("path").getAsString();
                    ZipEntry bundled = jar.getEntry("maven/" + path);
                    Path target = directory.resolve("libraries").resolve(path).normalize();
                    if (bundled == null || libraries.contains(target)) {
                        continue;
                    }
                    if (!target.startsWith(directory.resolve("libraries"))) {
                        throw new IOException("Forge library path " + path + " leaves the libraries folder");
                    }
                    ensure(target, artifact.get("sha1").getAsString(), file -> copy(jar, bundled, file), log);
                    libraries.add(target);
                }
            }

            ZipEntry patches = jar.getEntry("data/" + sideName + ".lzma");
            if (patches == null) {
                throw new IOException("Forge's installer has no " + sideName + " patches");
            }
            Path game = renamedGame(jar, release, side, vanillaGame, directory, downloader, log);
            Path stamp = directory.resolve(patched.getFileName() + ".input");
            String input = sha1(game) + " " + release.installerSha1();
            if (!Files.isRegularFile(patched)
                    || !Files.isRegularFile(stamp)
                    || !Files.readString(stamp).strip().equals(input)) {
                ForgePatchSet patchSet;
                try (InputStream in = jar.getInputStream(patches)) {
                    patchSet = ForgePatchSet.read(in);
                }
                patchSet.patch(
                        game,
                        patched,
                        side == GameSide.CLIENT ? Optional.of(".forge_patched_minecraft") : Optional.empty());
                Files.writeString(stamp, input);
                log.accept("Built " + patched.getFileName() + " (" + patchSet.size() + " Forge patches, "
                        + patchSet.newClasses() + " new classes)");
            }
            srgNames = srgNames(json(jar, "install_profile.json"), directory, sideName, log);
        }
        if (srgNames.isEmpty()) {
            return new Prepared(
                    release, directory, patched, List.copyOf(libraries), fileSystemJars, srgNames, fmlArguments);
        }
        // Forge 1.20.x names Minecraft's members in SRG; everything of Forge's that touches them is renamed to Mojang's
        // once (ADR-0038 point 5), so the game layer, the transformation rules and the Fabric bridge see one set of
        // names.
        SrgTranslator translator = new SrgTranslator(srgNames.get());
        Path namesFile = directory.resolve(namesFileName(sideName));
        String namesKey = fileKey(namesFile);
        Path renamed = directory.resolve("mojang-" + sideName);
        Path libraryRoot = directory.resolve("libraries");
        List<Path> translated = new ArrayList<>();
        for (Path library : libraries) {
            translated.add(
                    library.startsWith(libraryRoot.resolve("net").resolve("minecraftforge"))
                            ? translated(
                                    translator,
                                    library,
                                    renamed.resolve("libraries").resolve(libraryRoot.relativize(library)),
                                    namesKey,
                                    log)
                            : library);
        }
        Path game = translated(translator, patched, renamed.resolve(patched.getFileName()), namesKey, log);
        return new Prepared(release, directory, game, List.copyOf(translated), fileSystemJars, srgNames, fmlArguments);
    }

    /**
     * The jar Forge's binary patches apply to. For an obfuscated Minecraft version (Forge 1.21.1, 1.21.11, ADR-0038) it
     * is the obfuscated jar renamed by Forge's own tools ({@link ForgeInstallerTools}), made from the obfuscated jar
     * and mappings that {@code vanillaGame}, the jar Plexus Mod Loader remapped, was made from ({@link
     * ObfuscatedGameFiles}) unless the cached one has the same inputs. Otherwise {@code vanillaGame} itself.
     */
    private static Path renamedGame(
            JarFile installer,
            Release release,
            GameSide side,
            Path vanillaGame,
            Path directory,
            Downloader downloader,
            Consumer<String> log)
            throws IOException {
        String sideName = side == GameSide.CLIENT ? "client" : "server";
        JsonObject profile = json(installer, "install_profile.json");
        Optional<ForgeInstallerTools.Plan> plan = ForgeInstallerTools.plan(profile, sideName);
        if (plan.isEmpty()) {
            return vanillaGame;
        }
        ObfuscatedGameFiles obfuscated = ObfuscatedGameFiles.locate(vanillaGame, side, release.minecraftVersion())
                .orElseThrow(() -> new IOException("Forge " + release.forgeVersion()
                        + " patches the obfuscated Minecraft jar renamed by its own tool, but the obfuscated jar that "
                        + vanillaGame.getFileName() + " was made from was not found next to it"));
        Path renamed = directory.resolve("minecraft-" + sideName + "-official.jar");
        Path stamp = directory.resolve(renamed.getFileName() + ".input");
        String input = sha1(obfuscated.jar()) + " " + release.installerSha1();
        if (Files.isRegularFile(renamed)
                && Files.isRegularFile(stamp)
                && Files.readString(stamp).strip().equals(input)) {
            return renamed;
        }
        Files.deleteIfExists(stamp);
        Path libraries = directory.resolve("libraries");
        ForgeInstallerTools.run(
                plan.get(),
                release.minecraftVersion(),
                sideName,
                obfuscated.jar(),
                renamed,
                directory.resolve("tools"),
                name -> NeoForgeInstallation.profileLibrary(installer, profile, name, libraries, downloader, log),
                log);
        Files.writeString(stamp, input);
        // The names table is made from the files these tools just wrote.
        Files.deleteIfExists(directory.resolve(namesFileName(sideName)));
        return renamed;
    }

    /**
     * The mod jars FML runs: for Forge 1.20.x each jar with its SRG members renamed to Mojang's (kept in the cache, made
     * again when the jar changed); otherwise {@code mods} as they are. A jar without SRG names stays where it is.
     */
    static List<Path> forFml(Prepared files, GameSide side, List<Path> mods, Consumer<String> log) throws IOException {
        if (files.srgNames().isEmpty()) {
            return mods;
        }
        String sideName = side == GameSide.CLIENT ? "client" : "server";
        SrgTranslator translator = new SrgTranslator(files.srgNames().get());
        String namesKey = fileKey(files.directory().resolve(namesFileName(sideName)));
        Path root = files.directory().resolve("mojang-" + sideName).resolve("mods");
        List<Path> result = new ArrayList<>();
        for (Path mod : mods) {
            String where = Integer.toHexString(mod.toAbsolutePath().normalize().hashCode());
            result.add(translated(translator, mod, root.resolve(where).resolve(mod.getFileName()), namesKey, log));
        }
        return List.copyOf(result);
    }

    private static String namesFileName(String sideName) {
        return "srg-mojang-" + sideName + ".txt";
    }

    /**
     * The table that renames SRG members to Mojang's, when Forge's renaming pipeline for this version gives members SRG
     * names (it has a {@code MERGE_MAPPING} step: Forge 1.20.x). It is joined from the two mapping files the installer
     * tools wrote and kept next to them.
     */
    private static Optional<SrgMojangNames> srgNames(
            JsonObject profile, Path directory, String sideName, Consumer<String> log) throws IOException {
        Optional<ForgeInstallerTools.Plan> plan = ForgeInstallerTools.plan(profile, sideName);
        if (plan.isEmpty()
                || plan.get().steps().stream()
                        .noneMatch(step -> step.arguments().contains("MERGE_MAPPING"))) {
            return Optional.empty();
        }
        Path file = directory.resolve(namesFileName(sideName));
        if (!Files.isRegularFile(file)) {
            Path tools = directory.resolve("tools");
            Path merged = tools.resolve("data-MERGED_MAPPINGS");
            Path mojang = tools.resolve("data-MOJMAPS");
            if (!Files.isRegularFile(merged) || !Files.isRegularFile(mojang)) {
                throw new IOException("Forge's installer tools left no mapping files in " + tools
                        + "; delete the folder " + directory.getFileName() + " to make them again");
            }
            SrgMojangNames names = SrgMojangNames.join(merged, mojang);
            if (names.size() == 0) {
                throw new IOException("Forge's mapping files in " + tools + " gave no SRG member name a Mojang name;"
                        + " their form is not the one Plexus Mod Loader reads");
            }
            names.write(file);
            log.accept("Joined " + names.size() + " SRG member names of Minecraft to Mojang's (" + file.getFileName()
                    + ")");
        }
        return Optional.of(SrgMojangNames.read(file));
    }

    /** A cheap identity of a file that is replaced when it changes: size and modification time. */
    private static String fileKey(Path file) throws IOException {
        return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
    }

    /**
     * {@code input} with its SRG members renamed ({@link SrgTranslator}) in {@code output}, or {@code input} itself when
     * it has none. Kept unless {@code input} or the table changed.
     */
    private static Path translated(
            SrgTranslator translator, Path input, Path output, String namesKey, Consumer<String> log)
            throws IOException {
        Path stamp = output.resolveSibling(output.getFileName() + ".input");
        String key = fileKey(input) + " " + namesKey;
        if (Files.isRegularFile(stamp)) {
            String[] recorded = Files.readString(stamp).strip().split(" ");
            if (recorded.length == 3 && (recorded[0] + " " + recorded[1]).equals(key)) {
                if (recorded[2].equals("same")) {
                    return input;
                }
                if (Files.isRegularFile(output)) {
                    return output;
                }
            }
        }
        long start = System.nanoTime();
        boolean changed = translator.translateJar(input, output);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(stamp, key + (changed ? " translated" : " same"));
        if (changed) {
            log.accept("Renamed SRG members to Mojang's in " + input.getFileName() + " in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
        }
        return changed ? output : input;
    }

    /**
     * The {@code --fml.*} option pairs of {@code version.json}'s game arguments ({@code --fml.forgeVersion 47.4.28 --fml.mcVersion
     * 1.20.1 --fml.forgeGroup net.minecraftforge --fml.mcpVersion 20230612.114412}); empty when it has none.
     */
    static List<String> fmlArguments(JsonObject version) {
        List<String> result = new ArrayList<>();
        if (!version.has("arguments") || !version.getAsJsonObject("arguments").has("game")) {
            return result;
        }
        List<String> game = new ArrayList<>();
        for (JsonElement element : version.getAsJsonObject("arguments").getAsJsonArray("game")) {
            if (element.isJsonPrimitive()) {
                game.add(element.getAsString());
            }
        }
        for (int i = 0; i + 1 < game.size(); i++) {
            if (game.get(i).startsWith("--fml.") && !game.get(i + 1).startsWith("--")) {
                result.add(game.get(i));
                result.add(game.get(i + 1));
                i++;
            }
        }
        return List.copyOf(result);
    }

    /**
     * Where the jars Forge's file systems come from ({@link ForgeFileSystems#ARTIFACTS}) are below {@code libraries},
     * by the names and paths in the installer's {@code version.json}, in {@link ForgeFileSystems#ARTIFACTS} order.
     *
     * @throws IOException when {@code version} does not list one of them
     */
    static List<Path> fileSystemJars(JsonObject version, Path libraries) throws IOException {
        List<Path> jars = new ArrayList<>();
        boolean hasSecureJar = false;
        for (String artifact : ForgeFileSystems.ARTIFACTS) {
            Path found = null;
            for (JsonElement element : version.getAsJsonArray("libraries")) {
                JsonObject library = element.getAsJsonObject();
                String[] name = library.get("name").getAsString().split(":");
                if (name.length == 3 && artifact.equals(name[0] + ":" + name[1])) {
                    String path = library.getAsJsonObject("downloads")
                            .getAsJsonObject("artifact")
                            .get("path")
                            .getAsString();
                    found = libraries.resolve(path).normalize();
                }
            }
            if (found == null && ForgeFileSystems.OPTIONAL.contains(artifact)) {
                continue;
            }
            if (found == null) {
                throw new IOException("Forge's installer does not list " + artifact
                        + ", whose file system FML needs on the JVM class path");
            }
            jars.add(found);
            hasSecureJar |= ForgeFileSystems.SECURE_JARS.contains(artifact);
        }
        if (!hasSecureJar) {
            throw new IOException("Forge's installer lists none of " + ForgeFileSystems.SECURE_JARS
                    + ", whose file system FML needs on the JVM class path");
        }
        return List.copyOf(jars);
    }

    /** The default downloader: HTTPS through the JDK's client, following redirects. */
    static Downloader httpDownloader() {
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

    /** Writes one file's content to the path it is given. */
    @FunctionalInterface
    interface Fetch {
        void into(Path file) throws IOException;
    }

    /**
     * Keeps {@code target} when it has {@code sha1}; otherwise fetches it to a partial file, checks the hash and only
     * then moves it to {@code target}, so a damaged download never stays there.
     */
    static void ensure(Path target, String sha1, Fetch fetch, Consumer<String> log) throws IOException {
        if (Files.isRegularFile(target) && sha1(target).equalsIgnoreCase(sha1)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        try {
            Files.deleteIfExists(partial);
            fetch.into(partial);
            String actual = sha1(partial);
            if (!actual.equalsIgnoreCase(sha1)) {
                throw new IOException(target.getFileName() + " has SHA-1 " + actual + ", expected " + sha1
                        + "; the download is damaged or was changed");
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.accept("Downloaded " + target.getFileName());
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    static void copy(JarFile jar, ZipEntry entry, Path target) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static JsonObject json(JarFile jar, String name) throws IOException {
        ZipEntry entry = jar.getEntry(name);
        if (entry == null) {
            throw new IOException("Forge's installer has no " + name);
        }
        try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    static String sha1(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-1", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
