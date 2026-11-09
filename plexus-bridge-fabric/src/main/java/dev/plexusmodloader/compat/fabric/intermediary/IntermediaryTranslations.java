package dev.plexusmodloader.compat.fabric.intermediary;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.plexusmodloader.compat.bridge.CompatibilityFinding;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Makes Fabric-family mods built for Minecraft before 26.1 usable on this machine (ADR-0037): those mods name the game
 * by Fabric's intermediary names, and the game here has Mojang's names (ADR-0019). For a mod jar, {@link #prepare}
 * returns a copy that names the game in Mojang's names, made once on this machine and kept in {@code
 * <reportDir>/translated-mods/<minecraft>/}; for a jar that needs nothing, or on a game that is not obfuscated, the
 * jar itself.
 *
 * <p>What it needs, all found from the game jar ({@code plexus.gameJar}, or the one class path jar with a {@code
 * version.json}):
 *
 * <ul>
 *   <li>Mojang's mappings, which the installer or the build keeps next to the remapped game jar ({@code
 *       client-mappings-<minecraft>.txt}, {@code server-mappings-<minecraft>.txt}); {@code plexus.mojangMappings} names
 *       another file
 *   <li>FabricMC's intermediary mappings ({@code intermediary-<minecraft>-v2.jar}), kept in the same folder. When the
 *       file is not there it is downloaded once from FabricMC's Maven repository, checked by the SHA-1 the repository
 *       publishes, and kept. {@code plexus.intermediaryMappings} names a file to use instead
 * </ul>
 *
 * <p>The mappings and the translated jars stay on this machine and are never uploaded or distributed (ADR-0019 point 3).
 * A copy is reused while the mod jar (size and modification time), the mappings and {@link #REVISION} are the same.
 *
 * <p>Findings: {@code PLEXUS-COMPAT-142} (not blocking) a mod was translated; {@code -143} (blocking) a mod names
 * intermediary names and the mappings are missing; {@code -144} (blocking) the translation failed; {@code -145} (not
 * blocking) names that could not be decided.
 *
 * <p>Thread: safe from any thread.
 */
public final class IntermediaryTranslations {

    /** Revision of the translation rules; part of the cache key, raised when a copy would differ. */
    // 4: reference maps of Quilt {config, environment} mixin entries are translated too.
    public static final int REVISION = 4;

    /** A mod jar to use and what to tell the player about it. */
    public record Prepared(Path jar, List<CompatibilityFinding> findings) {

        public Prepared {
            findings = List.copyOf(findings);
        }
    }

    private sealed interface Environment {}

    private record NotApplicable() implements Environment {}

    private record Unavailable(String minecraft, String reason) implements Environment {}

    private record Ready(String minecraft, Tables tables, Path cache, String key) implements Environment {}

    /** The two name tables read and combined: about 33 to 48 MB of heap on the four obfuscated versions. */
    private record Loaded(IntermediaryTranslation translation, IntermediaryTranslation.Parents parents) {}

    /**
     * The name tables of this game, built the first time something needs them and let go of when nothing has for a
     * while. A launch whose mods are all translated already (the copies are found by name) never reads the mappings,
     * which saves 0.5 to 0.8 s; a launch that did translate lets the tables go when the mods are prepared ({@link
     * IntermediaryTranslations#releaseTables()}) or 30 seconds after the last use, so 33 to 48 MB are not held
     * for the rest of the game. Reading them again for a late {@code getMappingResolver()} call costs the same 0.5 to
     * 0.8 s, once per idle period.
     */
    private static final class Tables {

        private final Path gameJar;
        private final Path mojang;
        private final Path intermediary;
        /** How long unused tables stay in memory; {@code plexus.intermediaryIdleMillis} changes it (tests). */
        private final long idleMillis = Long.getLong("plexus.intermediaryIdleMillis", 30_000L);

        private Loaded loaded;
        private long lastUse;
        private boolean checkScheduled;

        Tables(Path gameJar, Path mojang, Path intermediary) {
            this.gameJar = gameJar;
            this.mojang = mojang;
            this.intermediary = intermediary;
        }

        /** The tables, read now if they are not in memory. */
        synchronized Loaded get() {
            if (loaded == null) {
                IntermediaryTranslation base = IntermediaryTranslation.of(
                        intermediary.toString().endsWith(".tiny")
                                ? IntermediaryMappings.readFile(intermediary)
                                : IntermediaryMappings.readJar(intermediary),
                        MojangNames.read(mojang));
                loaded = new Loaded(base, new GameParents(gameJar, base));
                tablesLoads++;
            }
            lastUse = System.nanoTime();
            if (!checkScheduled) {
                checkScheduled = true;
                reaper().schedule(this::releaseIfIdle, idleMillis, TimeUnit.MILLISECONDS);
            }
            return loaded;
        }

        /** Lets the tables go; the next {@link #get()} reads them again. */
        synchronized void release() {
            loaded = null;
        }

        synchronized boolean isLoaded() {
            return loaded != null;
        }

        private synchronized void releaseIfIdle() {
            checkScheduled = false;
            if (loaded == null) {
                return;
            }
            long unusedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastUse);
            if (unusedMillis >= idleMillis) {
                loaded = null;
            } else {
                checkScheduled = true;
                reaper().schedule(this::releaseIfIdle, idleMillis - unusedMillis, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** How many times the tables were read in this JVM; for tests and diagnostics. */
    private static volatile int tablesLoads;

    private static ScheduledExecutorService reaper;

    private static synchronized ScheduledExecutorService reaper() {
        if (reaper == null) {
            reaper = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "Plexus intermediary tables");
                thread.setDaemon(true);
                return thread;
            });
        }
        return reaper;
    }

    private static final String MAVEN = "https://maven.fabricmc.net";
    private static final Object LOCK = new Object();
    private static Environment environment;

    private IntermediaryTranslations() {}

    /**
     * The jar to assess, load and link-check in place of {@code modJar}: a translated copy when the game is obfuscated
     * and the mod names it by intermediary names, otherwise {@code modJar}. Never throws; a failure is a blocking
     * finding and the original jar.
     */
    public static Prepared prepare(Path modJar) {
        Environment env = environment();
        if (env instanceof NotApplicable) {
            return new Prepared(modJar, List.of());
        }
        if (env instanceof Unavailable unavailable) {
            try {
                if (!usesIntermediaryNames(modJar)) {
                    return new Prepared(modJar, List.of());
                }
            } catch (IOException | RuntimeException e) {
                return new Prepared(modJar, List.of());
            }
            return new Prepared(
                    modJar,
                    List.of(new CompatibilityFinding(
                            "PLEXUS-COMPAT-143",
                            true,
                            "intermediary",
                            "the mod is built for Minecraft " + unavailable.minecraft() + " with Fabric's"
                                    + " intermediary names and Plexus Mod Loader could not translate them to the names"
                                    + " this game has: " + unavailable.reason())));
        }
        Ready ready = (Ready) env;
        try {
            return translateCached(ready, modJar);
        } catch (IOException | RuntimeException e) {
            return new Prepared(
                    modJar,
                    List.of(new CompatibilityFinding(
                            "PLEXUS-COMPAT-144",
                            true,
                            "intermediary",
                            "translating the mod from Fabric's intermediary names to Mojang's names for Minecraft "
                                    + ready.minecraft() + " failed: " + e)));
        }
    }

    private static Prepared translateCached(Ready ready, Path modJar) throws IOException {
        String stem = modJar.getFileName().toString();
        if (stem.endsWith(".jar")) {
            stem = stem.substring(0, stem.length() - 4);
        }
        String stamp = Long.toHexString(Files.size(modJar)) + "-"
                + Long.toHexString(Files.getLastModifiedTime(modJar).toMillis());
        String name = stem + "-" + stamp + "-" + ready.key().substring(0, 8);
        Path translated = ready.cache().resolve(name + ".jar");
        Path same = ready.cache().resolve(name + ".same");
        if (Files.isRegularFile(same)) {
            touch(same);
            return new Prepared(modJar, List.of());
        }
        if (Files.isRegularFile(translated)) {
            touch(translated);
            return new Prepared(
                    translated,
                    List.of(new CompatibilityFinding(
                            "PLEXUS-COMPAT-142",
                            false,
                            "intermediary",
                            "built with Fabric's intermediary names; runs from a copy translated to Mojang's names for"
                                    + " Minecraft " + ready.minecraft() + " (" + translated + ")")));
        }
        Files.createDirectories(ready.cache());
        removeUnusedCopies(ready.cache());
        Loaded tables = ready.tables().get();
        IntermediaryTranslation view = tables.translation().withParents(tables.parents());
        ModJarTranslator.Result result = ModJarTranslator.translate(view, modJar, translated);
        if (!result.changed()) {
            Files.write(same, new byte[0]);
            return new Prepared(modJar, List.of());
        }
        List<CompatibilityFinding> findings = new ArrayList<>();
        findings.add(new CompatibilityFinding(
                "PLEXUS-COMPAT-142",
                false,
                "intermediary",
                "built with Fabric's intermediary names; translated " + result.classes() + " class(es) and "
                        + result.resources() + " Mixin reference map or class tweaker file(s) to Mojang's names for"
                        + " Minecraft " + ready.minecraft() + " on this computer (" + translated + ")"));
        if (view.unresolved() > 0) {
            findings.add(new CompatibilityFinding(
                    "PLEXUS-COMPAT-145",
                    false,
                    "intermediary",
                    view.unresolved() + " intermediary member name(s) name different Mojang members in unrelated"
                            + " classes and were left as written, such as " + view.unresolvedExamples()));
        }
        if (result.unreadable() > 0) {
            findings.add(new CompatibilityFinding(
                    "PLEXUS-COMPAT-145",
                    false,
                    "intermediary",
                    result.unreadable() + " class file(s) are newer than this translator reads and were copied as"
                            + " they are"));
        }
        return new Prepared(translated, findings);
    }

    /** Copies and markers nobody used for this long are deleted when a new copy is made. */
    private static final java.time.Duration UNUSED_FOR = java.time.Duration.ofDays(60);

    /**
     * Deletes copies and markers that no launch has used for {@link #UNUSED_FOR}, so that mods the player removed or
     * updated do not stay forever. A copy that is used is touched ({@link #touch}), so one in use is never old. Only
     * files of the shape this class writes ({@code <stem>-<size>-<time>-<key>.jar} or {@code .same}) go.
     */
    private static void removeUnusedCopies(Path cache) throws IOException {
        Pattern shape = Pattern.compile(".+-[0-9a-f]+-[0-9a-f]+-[0-9a-f]{8}\\.(jar|same)");
        java.nio.file.attribute.FileTime limit =
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(UNUSED_FOR));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(cache)) {
            for (Path file : files) {
                if (shape.matcher(file.getFileName().toString()).matches()
                        && Files.getLastModifiedTime(file).compareTo(limit) < 0) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** Marks a copy as used by this launch. */
    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        } catch (IOException e) {
            // Only the age of an unused copy depends on it.
        }
    }

    /** Whether a class file of the jar names a numbered Fabric class ({@code class_} and a digit). */
    static boolean usesIntermediaryNames(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    if (containsClassNumber(input.readAllBytes())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsClassNumber(byte[] bytes) {
        byte[] marker = "class_".getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + marker.length < bytes.length; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (bytes[i + j] != marker[j]) {
                    continue outer;
                }
            }
            byte next = bytes[i + marker.length];
            if (next >= '0' && next <= '9') {
                return true;
            }
        }
        return false;
    }

    private static Environment environment() {
        synchronized (LOCK) {
            if (environment == null) {
                environment = load();
            }
            return environment;
        }
    }

    /**
     * The translation of this game, when it is obfuscated and the mappings are on this machine: what {@code
     * FabricLoader.getMappingResolver()} uses to answer for the {@code intermediary} namespace. Empty on 26.x.
     */
    public static Optional<IntermediaryTranslation> translation() {
        if (!(environment() instanceof Ready ready)) {
            return Optional.empty();
        }
        try {
            return Optional.of(ready.tables().get().translation());
        } catch (RuntimeException e) {
            // The mappings on this machine cannot be read: the resolver then answers as on 26.x, and prepare() has
            // already told the player (PLEXUS-COMPAT-144) about every mod that needed them.
            return Optional.empty();
        }
    }

    /**
     * Whether {@link #translation()} has an answer for this game, without reading the mappings: the game is obfuscated
     * and both mapping files are on this machine.
     */
    public static boolean available() {
        return environment() instanceof Ready;
    }

    /**
     * Lets the parsed name tables go (33 to 48 MB of heap). The runtime calls it once every mod jar of the loader has
     * been prepared ({@link #prepare} is called for all of them before the game starts); a later {@link #translation()}
     * reads the tables again. Does nothing when no tables are in memory.
     */
    public static void releaseTables() {
        Environment env;
        synchronized (LOCK) {
            env = environment;
        }
        if (env instanceof Ready ready) {
            ready.tables().release();
        }
    }

    /** Whether the parsed name tables are in memory now; for tests and diagnostics. */
    static boolean tablesLoaded() {
        Environment env;
        synchronized (LOCK) {
            env = environment;
        }
        return env instanceof Ready ready && ready.tables().isLoaded();
    }

    /** How many times the parsed name tables were read in this JVM; for tests and diagnostics. */
    static int tablesLoads() {
        return tablesLoads;
    }

    /** Forgets what was found about the game, for tests that change the properties; the runtime never calls it. */
    public static void reset() {
        synchronized (LOCK) {
            environment = null;
        }
    }

    private static Environment load() {
        Optional<Path> located = locateGameJar();
        if (located.isEmpty()) {
            return new NotApplicable();
        }
        Path gameJar = located.get();
        String minecraft = minecraftVersion(gameJar);
        if (minecraft == null || !isObfuscated(minecraft)) {
            return new NotApplicable();
        }
        try {
            Path mojang = mojangMappings(gameJar, minecraft);
            if (mojang == null) {
                return new Unavailable(
                        minecraft,
                        "Mojang's mappings for this game are not next to " + gameJar.getFileName()
                                + " (the installer keeps client-mappings-" + minecraft + ".txt or server-mappings-"
                                + minecraft + ".txt there); install the Plexus Mod Loader profile or server again");
            }
            Path intermediary = intermediaryMappings(gameJar, minecraft);
            // The tables are read when a mod needs a translation (Tables); the key only needs the files' hashes.
            String key = sha1(sha1(mojang) + "|" + sha1(intermediary) + "|" + REVISION);
            // Absolute, so a copy's path (and the class path entry and origin a mod sees) does not depend on the
            // working directory of whoever asks.
            Path cache = Path.of(System.getProperty("plexus.reportDir", ".plexus"))
                    .toAbsolutePath()
                    .normalize()
                    .resolve("translated-mods")
                    .resolve(minecraft);
            return new Ready(minecraft, new Tables(gameJar, mojang, intermediary), cache, key);
        } catch (IOException | RuntimeException e) {
            return new Unavailable(minecraft, String.valueOf(e.getMessage() == null ? e : e.getMessage()));
        }
    }

    /** {@code plexus.gameJar}, or the one class path jar (manifest {@code Class-Path} included) with a {@code version.json}. */
    static Optional<Path> locateGameJar() {
        String setting = System.getProperty("plexus.gameJar");
        if (setting != null && !setting.isBlank()) {
            Path path = Path.of(setting).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        }
        List<Path> candidates = new ArrayList<>();
        Deque<Path> pending = new ArrayDeque<>();
        Arrays.stream(System.getProperty("java.class.path", "").split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .forEach(pending::add);
        Set<Path> seen = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            Path path = pending.removeFirst().toAbsolutePath().normalize();
            if (!seen.add(path) || !Files.isRegularFile(path)) {
                continue;
            }
            try (JarFile jar = new JarFile(path.toFile())) {
                if (jar.getEntry("version.json") != null) {
                    candidates.add(path);
                }
                pending.addAll(manifestClassPath(path, jar.getManifest()));
            } catch (IOException | IllegalArgumentException e) {
                // Not a readable jar: then it is not the game jar either.
            }
        }
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private static List<Path> manifestClassPath(Path jar, Manifest manifest) {
        if (manifest == null) {
            return List.of();
        }
        String value = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        URI base = jar.toUri();
        List<Path> entries = new ArrayList<>();
        for (String entry : value.strip().split("\\s+")) {
            URI resolved = base.resolve(entry);
            if ("file".equals(resolved.getScheme())) {
                entries.add(Path.of(resolved));
            }
        }
        return entries;
    }

    /** The {@code id} of the game jar's {@code version.json}, or {@code null}. */
    static String minecraftVersion(Path gameJar) {
        try (ZipFile zip = new ZipFile(gameJar.toFile())) {
            ZipEntry entry = zip.getEntry("version.json");
            if (entry == null) {
                return null;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                JsonElement root = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                if (root.isJsonObject()) {
                    JsonObject object = root.getAsJsonObject();
                    for (String key : List.of("id", "name")) {
                        if (object.has(key) && object.get(key).isJsonPrimitive()) {
                            return object.get(key).getAsString();
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Before 26.1 Minecraft ships obfuscated: every version whose first number is below 26. */
    static boolean isObfuscated(String minecraft) {
        int end = 0;
        while (end < minecraft.length() && Character.isDigit(minecraft.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return false;
        }
        return Integer.parseInt(minecraft.substring(0, end)) < 26;
    }

    private static Path mojangMappings(Path gameJar, String minecraft) {
        String setting = System.getProperty("plexus.mojangMappings");
        if (setting != null && !setting.isBlank()) {
            Path path = Path.of(setting);
            return Files.isRegularFile(path) ? path : null;
        }
        Path folder = gameJar.getParent();
        boolean server = gameJar.getFileName().toString().startsWith("server");
        Path client = folder.resolve("client-mappings-" + minecraft + ".txt");
        Path serverFile = folder.resolve("server-mappings-" + minecraft + ".txt");
        for (Path candidate : server ? List.of(serverFile, client) : List.of(client, serverFile)) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path intermediaryMappings(Path gameJar, String minecraft) throws IOException {
        String setting = System.getProperty("plexus.intermediaryMappings");
        if (setting != null && !setting.isBlank()) {
            return Path.of(setting);
        }
        Path file = gameJar.getParent().resolve("intermediary-" + minecraft + "-v2.jar");
        if (Files.isRegularFile(file)) {
            return file;
        }
        download(minecraft, file);
        return file;
    }

    /**
     * Downloads FabricMC's intermediary mappings jar for {@code minecraft}, checks it against the SHA-1 the repository
     * publishes next to it, and keeps it at {@code target}. {@code plexus.intermediaryUrl} replaces the repository.
     */
    private static void download(String minecraft, Path target) throws IOException {
        String repository = System.getProperty("plexus.intermediaryUrl", MAVEN);
        String url = repository + "/net/fabricmc/intermediary/" + minecraft + "/intermediary-" + minecraft + "-v2.jar";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        try {
            String expected = new String(send(client, url + ".sha1").readAllBytes(), StandardCharsets.US_ASCII).strip();
            if (expected.length() < 40) {
                throw new IOException("the repository's checksum for " + url + " is not a SHA-1");
            }
            expected = expected.substring(0, 40).toLowerCase(java.util.Locale.ROOT);
            Files.createDirectories(target.getParent());
            try (InputStream input = send(client, url)) {
                Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            String actual = sha1(partial);
            if (!actual.equals(expected)) {
                throw new IOException("the download of " + url + " has SHA-1 " + actual + " instead of " + expected);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading the intermediary mappings from " + url, e);
        } catch (IOException e) {
            throw new IOException(
                    "FabricMC's intermediary mappings for Minecraft " + minecraft + " are not on this computer and"
                            + " could not be downloaded from " + url + " (" + e.getMessage() + "); start once while"
                            + " online, or put the file at " + target,
                    e);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static InputStream send(HttpClient client, String url) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(120))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    private static String sha1(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha1(String text) {
        return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JDK has no SHA-1", e);
        }
    }
}
