package dev.plexusmodloader.compat.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.plexusmodloader.api.runtime.GameSide;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * NeoForge's files for one Minecraft version, put together on the player's PC the way NeoForge's installer does
 * (ADR-0026): the installer from NeoForge's Maven (checked against the SHA-1 Plexus Mod Loader pins), the runtime
 * libraries its {@code version.json} lists and NeoForge's {@code universal.jar} from its {@code install_profile.json}
 * (each checked against the SHA-1 given there), and the game jar with NeoForge's patches applied ({@link
 * NeoForgePatchBundle}). For a server, also the libraries of {@code install_profile.json} that NeoForge's own server
 * puts on its class path ({@link #serverClassPathLibraries}). The libraries folder has the installer's layout, which FML's production game locator reads
 * ({@code -DlibraryDirectory}). Everything is cached in one folder per NeoForge version; a later launch only checks
 * hashes. Plexus Mod Loader itself ships none of these files.
 *
 * <p>Thread: one launch prepares at a time; the methods are not safe for concurrent use on the same folder.
 */
final class NeoForgeInstallation {

    static final String MAVEN = "https://maven.neoforged.net/releases/";

    /** The NeoForge release Plexus Mod Loader uses for a Minecraft version ({@code neoforge-releases.properties}). */
    record Release(String minecraftVersion, String neoForgeVersion, String installerSha1) {

        URI installerUri() {
            return URI.create(MAVEN + "net/neoforged/neoforge/" + neoForgeVersion + "/neoforge-" + neoForgeVersion
                    + "-installer.jar");
        }
    }

    /**
     * The prepared files.
     *
     * @param libraryDirectory the folder with the installer's layout, for FML's {@code libraryDirectory}
     * @param patchedGame the game jar with NeoForge's patches, where FML's game locator looks for it ({@code
     *     net/neoforged/minecraft-<side>-patched/<version>/})
     * @param universal NeoForge's {@code universal.jar}, which FML's game locator finds next to it
     * @param libraries NeoForge's runtime libraries in {@code version.json} order, then, for a server, the other
     *     libraries on NeoForge's own server class path
     * @param gameArguments the program arguments NeoForge's launcher profile adds ({@code --fml.neoForgeVersion} and
     *     the others FML's production game locator reads)
     */
    record Prepared(
            Release release,
            Path directory,
            Path libraryDirectory,
            Path patchedGame,
            Path universal,
            List<Path> libraries,
            List<String> gameArguments) {
        Prepared {
            libraries = List.copyOf(libraries);
            gameArguments = List.copyOf(gameArguments);
        }
    }

    private NeoForgeInstallation() {}

    /** The NeoForge release for {@code minecraftVersion}, or empty when Plexus Mod Loader has none for it. */
    static Optional<Release> release(String minecraftVersion) {
        Properties releases = new Properties();
        try (InputStream in = NeoForgeInstallation.class.getResourceAsStream("neoforge-releases.properties")) {
            if (in == null) {
                return Optional.empty();
            }
            releases.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read neoforge-releases.properties", e);
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
     * @param cacheRoot the folder that holds one folder per NeoForge version
     * @param side which game jar {@code vanillaGame} is
     * @param vanillaGame the game jar the patches apply to (the dedicated server's classes, or the client jar)
     * @param log receives one line per step
     * @throws IOException when a download fails, a file does not have the expected SHA-1, or a patch does not apply
     */
    static Prepared prepare(
            Release release,
            Path cacheRoot,
            GameSide side,
            Path vanillaGame,
            ForgeInstallation.Downloader downloader,
            Consumer<String> log)
            throws IOException {
        Path directory = cacheRoot.resolve(release.neoForgeVersion());
        Path libraryDirectory = directory.resolve("libraries");
        Files.createDirectories(libraryDirectory);
        Path installer = directory.resolve("neoforge-" + release.neoForgeVersion() + "-installer.jar");
        ForgeInstallation.ensure(
                installer, release.installerSha1(), file -> downloader.download(release.installerUri(), file), log);

        String sideName = side == GameSide.CLIENT ? "client" : "server";
        String patchedName = "minecraft-" + sideName + "-patched";
        Path patched = libraryDirectory
                .resolve("net/neoforged")
                .resolve(patchedName)
                .resolve(release.neoForgeVersion())
                .resolve(patchedName + "-" + release.neoForgeVersion() + ".jar");
        List<Path> libraries = new ArrayList<>();
        Path universal = null;
        List<String> gameArguments = new ArrayList<>();
        try (JarFile jar = new JarFile(installer.toFile())) {
            JsonObject version = ForgeInstallation.json(jar, "version.json");
            for (JsonElement element : version.getAsJsonArray("libraries")) {
                libraries.add(library(jar, element.getAsJsonObject(), libraryDirectory, downloader, log));
            }
            if (side != GameSide.CLIENT) {
                for (JsonObject library : serverClassPathLibraries(jar)) {
                    libraries.add(library(jar, library, libraryDirectory, downloader, log));
                }
            }
            JsonObject profile = ForgeInstallation.json(jar, "install_profile.json");
            String universalName = "net.neoforged:neoforge:" + release.neoForgeVersion() + ":universal";
            for (JsonElement element : profile.getAsJsonArray("libraries")) {
                if (withoutExtension(element.getAsJsonObject().get("name").getAsString())
                        .equals(universalName)) {
                    universal = library(jar, element.getAsJsonObject(), libraryDirectory, downloader, log);
                }
            }
            if (universal == null) {
                throw new IOException("NeoForge's installer does not list " + universalName);
            }
            for (JsonElement argument : version.getAsJsonObject("arguments").getAsJsonArray("game")) {
                gameArguments.add(argument.getAsString());
            }

            Optional<NeoForgeInstallerTools.Plan> renaming = NeoForgeInstallerTools.plan(profile, sideName);
            if (renaming.isPresent()) {
                // An obfuscated Minecraft version (NeoForge 21.11, ADR-0038): NeoForge's own tool renames the
                // obfuscated
                // jar and patches it; the jar Plexus Mod Loader remapped is not the one the patches check.
                buildRenamed(
                        renaming.get(),
                        jar,
                        profile,
                        release,
                        side,
                        vanillaGame,
                        directory,
                        libraryDirectory,
                        patched,
                        downloader,
                        log);
                return new Prepared(
                        release,
                        directory,
                        libraryDirectory,
                        patched,
                        universal,
                        libraries,
                        List.copyOf(gameArguments));
            }
            Optional<ForgeInstallerTools.Plan> classic = ForgeInstallerTools.plan(profile, sideName);
            if (classic.isPresent()) {
                // NeoForge 21.1 and 20.4 (ADR-0043): the installer renames the obfuscated jar with its own tools and
                // applies classic LZMA binary patches (Forge's format), not the patch bundle of NeoForge 21.11.
                buildClassic(
                        classic.get(),
                        jar,
                        profile,
                        release,
                        side,
                        vanillaGame,
                        directory,
                        libraryDirectory,
                        patched,
                        downloader,
                        log);
                return new Prepared(
                        release,
                        directory,
                        libraryDirectory,
                        patched,
                        universal,
                        libraries,
                        List.copyOf(gameArguments));
            }
            ZipEntry patches = jar.getEntry("data/client.lzma");
            if (patches == null) {
                throw new IOException("NeoForge's installer has no data/client.lzma");
            }
            Path stamp = patched.resolveSibling(patched.getFileName() + ".input");
            String input = ForgeInstallation.sha1(vanillaGame) + " " + release.installerSha1();
            if (!Files.isRegularFile(patched)
                    || !Files.isRegularFile(stamp)
                    || !Files.readString(stamp).strip().equals(input)) {
                NeoForgePatchBundle bundle;
                try (InputStream in = jar.getInputStream(patches)) {
                    bundle = NeoForgePatchBundle.read(in);
                }
                List<String> changed = bundle.patch(
                        vanillaGame,
                        patched,
                        side == GameSide.CLIENT ? NeoForgePatchBundle.Side.CLIENT : NeoForgePatchBundle.Side.SERVER);
                Files.writeString(stamp, input);
                log.accept("Built " + patched.getFileName() + " (" + changed.size() + " of " + bundle.size()
                        + " NeoForge patch entries for the " + sideName + ")");
            }
        }
        return new Prepared(
                release, directory, libraryDirectory, patched, universal, libraries, List.copyOf(gameArguments));
    }

    /**
     * Makes the patched game jar of an obfuscated version with NeoForge's own {@code installertools} (see {@link
     * NeoForgeInstallerTools}), unless the cached one was made from the same obfuscated jar, mappings and installer.
     *
     * @param remappedGame the jar Plexus Mod Loader remapped to Mojang names, from which the obfuscated jar and the
     *     mappings it was made from are found ({@link ObfuscatedGameFiles})
     */
    private static void buildRenamed(
            NeoForgeInstallerTools.Plan plan,
            JarFile installer,
            JsonObject profile,
            Release release,
            GameSide side,
            Path remappedGame,
            Path directory,
            Path libraryDirectory,
            Path patched,
            ForgeInstallation.Downloader downloader,
            Consumer<String> log)
            throws IOException {
        ObfuscatedGameFiles obfuscated = ObfuscatedGameFiles.locate(remappedGame, side, release.minecraftVersion())
                .orElseThrow(() -> new IOException("NeoForge " + release.neoForgeVersion()
                        + " patches the obfuscated Minecraft jar renamed by its own tool, but the obfuscated jar and"
                        + " Mojang's mappings that " + remappedGame.getFileName() + " was made from were not found"
                        + " next to it"));
        Path stamp = patched.resolveSibling(patched.getFileName() + ".input");
        String input = ForgeInstallation.sha1(obfuscated.jar()) + " " + ForgeInstallation.sha1(obfuscated.mappings())
                + " " + release.installerSha1();
        if (Files.isRegularFile(patched)
                && Files.isRegularFile(stamp)
                && Files.readString(stamp).strip().equals(input)) {
            return;
        }
        Path tools = profileLibrary(installer, profile, plan.toolsLibrary(), libraryDirectory, downloader, log);
        Path neoformData = profileLibrary(installer, profile, plan.neoformData(), libraryDirectory, downloader, log);
        ZipEntry patchEntry = installer.getEntry(plan.patches());
        if (patchEntry == null) {
            throw new IOException("NeoForge's installer has no " + plan.patches());
        }
        Path patches = directory.resolve(plan.patches());
        Files.createDirectories(patches.getParent());
        ForgeInstallation.copy(installer, patchEntry, patches);
        Files.deleteIfExists(stamp);
        NeoForgeInstallerTools.run(
                tools, obfuscated.jar(), obfuscated.mappings(), neoformData, patches, libraryDirectory, patched, log);
        Files.writeString(stamp, input);
    }

    /**
     * Makes the patched game jar of NeoForge 21.1 / 20.4 the way Forge's is made: NeoForge's own tools rename the
     * obfuscated jar ({@link ForgeInstallerTools}, the profile's steps), then the classic binary patches of {@code
     * data/<side>.lzma} apply ({@link ForgePatchSet}). Kept unless the obfuscated jar, mappings or installer changed.
     */
    private static void buildClassic(
            ForgeInstallerTools.Plan plan,
            JarFile installer,
            JsonObject profile,
            Release release,
            GameSide side,
            Path remappedGame,
            Path directory,
            Path libraryDirectory,
            Path patched,
            ForgeInstallation.Downloader downloader,
            Consumer<String> log)
            throws IOException {
        String sideName = side == GameSide.CLIENT ? "client" : "server";
        ObfuscatedGameFiles obfuscated = ObfuscatedGameFiles.locate(remappedGame, side, release.minecraftVersion())
                .orElseThrow(() -> new IOException("NeoForge " + release.neoForgeVersion()
                        + " patches the obfuscated Minecraft jar renamed by its own tool, but the obfuscated jar that "
                        + remappedGame.getFileName() + " was made from was not found next to it"));
        ZipEntry patchEntry = installer.getEntry("data/" + sideName + ".lzma");
        if (patchEntry == null) {
            throw new IOException("NeoForge's installer has no data/" + sideName + ".lzma");
        }
        Path stamp = patched.resolveSibling(patched.getFileName() + ".input");
        String input = ForgeInstallation.sha1(obfuscated.jar()) + " " + release.installerSha1();
        if (Files.isRegularFile(patched)
                && Files.isRegularFile(stamp)
                && Files.readString(stamp).strip().equals(input)) {
            layOutForFml4(profile, sideName, patched, libraryDirectory);
            return;
        }
        Files.deleteIfExists(stamp);
        Path renamed = directory.resolve("minecraft-" + sideName + "-official.jar");
        ForgeInstallerTools.run(
                plan,
                release.minecraftVersion(),
                sideName,
                obfuscated.jar(),
                renamed,
                directory.resolve("tools"),
                name -> profileLibrary(installer, profile, name, libraryDirectory, downloader, log),
                log);
        ForgePatchSet patchSet;
        try (InputStream in = installer.getInputStream(patchEntry)) {
            patchSet = ForgePatchSet.read(in);
        }
        patchSet.patch(renamed, patched);
        Files.writeString(stamp, input);
        log.accept("Built " + patched.getFileName() + " (" + patchSet.size() + " NeoForge patches, "
                + patchSet.newClasses() + " new classes)");
        layOutForFml4(profile, sideName, patched, libraryDirectory);
    }

    /**
     * Puts the game where FML 4's production handlers look for it, as NeoForge's installer does: {@code MC_SRG} (the
     * renamed game, {@code net.minecraft:<side>:<version>:srg}), {@code MC_EXTRA} and {@code PATCHED} ({@code
     * net.neoforged:neoforge:<version>:<side>}) under the libraries folder. The installer keeps the renamed classes, the
     * other files and NeoForge's patched classes in three jars; here the first is the whole patched game ({@code
     * patched}) and the other two are empty, which FML reads as the same game.
     */
    static void layOutForFml4(JsonObject profile, String sideName, Path patched, Path libraryDirectory)
            throws IOException {
        JsonObject data = profile.getAsJsonObject("data");
        Path srg = coordinatePath(
                libraryDirectory, data.getAsJsonObject("MC_SRG").get(sideName).getAsString());
        long size = Files.size(patched);
        if (!Files.isRegularFile(srg) || Files.size(srg) != size) {
            Files.createDirectories(srg.getParent());
            Files.copy(patched, srg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        for (String key : List.of("MC_EXTRA", "PATCHED")) {
            Path empty = coordinatePath(
                    libraryDirectory, data.getAsJsonObject(key).get(sideName).getAsString());
            if (!Files.isRegularFile(empty)) {
                Files.createDirectories(empty.getParent());
                try (java.util.zip.ZipOutputStream zip =
                        new java.util.zip.ZipOutputStream(Files.newOutputStream(empty))) {
                    zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                    zip.write("Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
        }
    }

    /**
     * The file of a Maven coordinate of the installer profile in {@code [group:artifact:version:classifier]} form (with
     * {@code @extension} optional), in the libraries folder layout.
     */
    static Path coordinatePath(Path libraryDirectory, String coordinate) {
        String value = coordinate.startsWith("[") && coordinate.endsWith("]")
                ? coordinate.substring(1, coordinate.length() - 1)
                : coordinate;
        String extension = "jar";
        int at = value.indexOf('@');
        if (at >= 0) {
            extension = value.substring(at + 1);
            value = value.substring(0, at);
        }
        String[] parts = value.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Not a Maven coordinate: " + coordinate);
        }
        String file = parts[1] + "-" + parts[2] + (parts.length > 3 ? "-" + parts[3] : "") + "." + extension;
        return libraryDirectory
                .resolve(parts[0].replace('.', '/'))
                .resolve(parts[1])
                .resolve(parts[2])
                .resolve(file);
    }

    /** The entry of {@code install_profile.json}'s {@code libraries} named {@code name}, downloaded and checked. */
    static Path profileLibrary(
            JarFile installer,
            JsonObject profile,
            String name,
            Path libraryDirectory,
            ForgeInstallation.Downloader downloader,
            Consumer<String> log)
            throws IOException {
        for (JsonElement element : profile.getAsJsonArray("libraries")) {
            if (withoutExtension(element.getAsJsonObject().get("name").getAsString())
                    .equals(withoutExtension(name))) {
                return library(installer, element.getAsJsonObject(), libraryDirectory, downloader, log);
            }
        }
        throw new IOException("NeoForge's installer does not list " + name);
    }

    /** A Maven coordinate without its {@code @jar} extension: NeoForge 20.4's profile names its libraries with one. */
    static String withoutExtension(String coordinate) {
        int at = coordinate.indexOf('@');
        return at < 0 ? coordinate : coordinate.substring(0, at);
    }

    /**
     * The libraries of {@code install_profile.json} that NeoForge's own server starts with besides those of {@code
     * version.json}: the entries the installer writes into the server's class path ({@code -classpath} of {@code
     * data/win_args.txt}, as {@code libraries/<path>}). For NeoForge 26.1.x these are LWJGL's core and tinyfd, for 26.2
     * tinyfd; the rest of that class path is Minecraft's own server libraries, which Plexus Mod Loader's server already has.
     * Empty when the installer has no server arguments.
     */
    static List<JsonObject> serverClassPathLibraries(JarFile installer) throws IOException {
        ZipEntry arguments = installer.getEntry("data/win_args.txt");
        if (arguments == null) {
            return List.of();
        }
        List<String> lines;
        try (InputStream in = installer.getInputStream(arguments)) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .toList();
        }
        int classPath = lines.indexOf("-classpath");
        if (classPath < 0 || classPath + 1 >= lines.size()) {
            return List.of();
        }
        Set<String> onClassPath = new LinkedHashSet<>();
        for (String entry : lines.get(classPath + 1).split(";")) {
            if (entry.startsWith("libraries/")) {
                onClassPath.add(entry.substring("libraries/".length()));
            }
        }
        Set<String> inVersion = new HashSet<>();
        if (installer.getEntry("version.json") != null) {
            for (JsonElement element :
                    ForgeInstallation.json(installer, "version.json").getAsJsonArray("libraries")) {
                inVersion.add(artifactPath(element.getAsJsonObject()));
            }
        }
        List<JsonObject> libraries = new ArrayList<>();
        for (JsonElement element :
                ForgeInstallation.json(installer, "install_profile.json").getAsJsonArray("libraries")) {
            JsonObject library = element.getAsJsonObject();
            String path = artifactPath(library);
            if (onClassPath.contains(path) && !inVersion.contains(path)) {
                libraries.add(library);
            }
        }
        return List.copyOf(libraries);
    }

    private static String artifactPath(JsonObject library) {
        return library.getAsJsonObject("downloads")
                .getAsJsonObject("artifact")
                .get("path")
                .getAsString();
    }

    /** Downloads (or copies from the installer) one library entry of {@code version.json} or the install profile. */
    static Path library(
            JarFile installer,
            JsonObject library,
            Path libraryDirectory,
            ForgeInstallation.Downloader downloader,
            Consumer<String> log)
            throws IOException {
        String name = library.get("name").getAsString();
        JsonObject artifact = library.getAsJsonObject("downloads").getAsJsonObject("artifact");
        String path = artifact.get("path").getAsString();
        String sha1 = artifact.get("sha1").getAsString();
        String url = artifact.has("url") ? artifact.get("url").getAsString() : "";
        Path target = libraryDirectory.resolve(path).normalize();
        if (!target.startsWith(libraryDirectory)) {
            throw new IOException("NeoForge library path " + path + " leaves the libraries folder");
        }
        ZipEntry bundled = installer.getEntry("maven/" + path);
        if (url.isEmpty() && bundled == null) {
            throw new IOException("NeoForge's installer names " + name + " but has neither a URL nor a copy");
        }
        ForgeInstallation.ensure(
                target,
                sha1,
                bundled != null
                        ? file -> ForgeInstallation.copy(installer, bundled, file)
                        : file -> downloader.download(URI.create(url), file),
                log);
        return target;
    }
}
