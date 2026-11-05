package dev.plexusmodloader.earlywindow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Plexus Mod Loader's early loading window, seen from the game's process: it starts the window's own process (see
 * {@link EarlyWindowMain}) and tells it what to show. The window looks like the game's loading screen with Plexus Mod
 * Loader's marks, so that it carries on into the game's screen when the game's window opens over it.
 *
 * <p>Every method may be called from any thread. Nothing here fails the launch: when the process cannot start or
 * stops, the methods do nothing and {@code log} gets one line saying why.
 */
public final class EarlyWindow implements AutoCloseable {

    private static final String MAIN_CLASS = EarlyWindowMain.class.getName();
    static final long WATCHDOG_TIMEOUT_MILLIS = 5_000L;

    private static final Set<String> JVM_OPTION_ENV_VARS =
            Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");

    private final Process process;
    private final Writer input;
    private final Consumer<String> log;
    private final long startedAt;
    private boolean closed;

    EarlyWindow(Process process, Consumer<String> log, long startedAt) {
        this.process = process;
        this.input = process == null ? null : new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        this.log = log;
        this.closed = process == null;
        this.startedAt = startedAt;
    }

    /** A window that shows nothing, for launches without one. */
    public static EarlyWindow none() {
        return new EarlyWindow(null, line -> {}, 0);
    }

    /**
     * Starts the window's process with the window hidden until {@link #show()}.
     *
     * @param log receives the window process's own output and one line when the window cannot be shown
     */
    public static EarlyWindow start(EarlyWindowSettings settings, Consumer<String> log) {
        Optional<Path> java = javaCommand();
        Optional<String> classPath = classPath();
        if (java.isEmpty() || classPath.isEmpty()) {
            log.accept("Early loading window not shown: Java executable or classpath not found");
            return none();
        }
        List<String> command = buildCommandLine(java.get(), classPath.get(), settings, Platform.current());
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
            applyEnvironment(processBuilder.environment(), sanitizeEnvironment(processBuilder.environment()));
            Process process = processBuilder.start();
            long startedAt = System.currentTimeMillis();
            forwardOutput(process, log);
            EarlyWindow window = new EarlyWindow(process, log, startedAt);
            window.watchStartup();
            Runtime.getRuntime().addShutdownHook(new Thread(window::destroy, "Plexus early window shutdown"));
            return window;
        } catch (IOException | RuntimeException e) {
            log.accept("Failed to start early loading window process " + "(" + e + ")");
            return none();
        }
    }

    static List<String> buildCommandLine(Path java, String classPath, EarlyWindowSettings settings, Platform platform) {
        List<String> command = new ArrayList<>(List.of(
                java.toString(),
                "-Xms8m",
                "-Xmx64m",
                "-XX:+UseSerialGC",
                "-XX:TieredStopAtLevel=1",
                "-Djava.awt.headless=false",
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8"));
        if (platform == Platform.MAC) {
            command.add("-Dapple.awt.UIElement=true");
        } else {
            // The game's window is sized in screen pixels on Windows and Linux; so is this one.
            command.add("-Dsun.java2d.uiScale=1");
        }
        command.addAll(List.of("-cp", classPath, MAIN_CLASS));
        command.addAll(settings.toArguments());
        return List.copyOf(command);
    }

    static boolean isJvmOptionEnvVar(String name) {
        for (String var : JVM_OPTION_ENV_VARS) {
            if (var.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    static String sanitizeJvmOptions(String options) {
        if (options == null || !options.contains("-XstartOnFirstThread")) {
            return options;
        }
        return options.replaceAll("(?:^|\\s+)-XstartOnFirstThread(?=\\s|$)", "").strip();
    }

    static Map<String, String> sanitizeEnvironment(Map<String, String> environment) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (isJvmOptionEnvVar(key)) {
                if (value != null) {
                    String cleaned = sanitizeJvmOptions(value);
                    if (!cleaned.isBlank()) {
                        sanitized.put(key, cleaned);
                    }
                }
            } else {
                sanitized.put(key, value);
            }
        }
        return Map.copyOf(sanitized);
    }

    static void applyEnvironment(Map<String, String> target, Map<String, String> desired) {
        target.keySet().removeIf(key -> isJvmOptionEnvVar(key) && !desired.containsKey(key));
        target.putAll(desired);
    }

    void watchStartup() {
        if (process == null) {
            return;
        }
        process.onExit().thenAccept(p -> {
            synchronized (this) {
                if (!closed && (System.currentTimeMillis() - startedAt < WATCHDOG_TIMEOUT_MILLIS)) {
                    log.accept("Early loading window process exited prematurely with code " + p.exitValue());
                }
            }
        });
    }

    /** Whether the window's process runs. */
    public synchronized boolean isRunning() {
        return !closed && process != null && process.isAlive();
    }

    /** Opens the window. */
    public void show() {
        send(EarlyWindowProtocol.show());
    }

    /** What Plexus Mod Loader is doing, shown at the bottom left. */
    public void status(String text) {
        send(EarlyWindowProtocol.status(text));
    }

    /** The progress bar's filled share, {@code 0} to {@code 1}; it never goes back. */
    public void progress(float value) {
        send(EarlyWindowProtocol.progress(value));
    }

    /** The lines under the progress bar. */
    public void underBar(List<String> lines) {
        send(EarlyWindowProtocol.underBar(lines));
    }

    /** Writes what the window shows to {@code file}, a PNG image (for checks). */
    public void snapshot(Path file) {
        send(EarlyWindowProtocol.snapshot(file.toAbsolutePath().toString()));
    }

    /** Closes the window and ends its process. Further calls do nothing. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (input != null) {
            try {
                input.write(EarlyWindowProtocol.close());
                input.write('\n');
                input.flush();
                input.close();
            } catch (IOException e) {
                // The process has gone already; it closes its window when it ends.
            }
        }
    }

    private synchronized void send(String line) {
        if (closed || input == null) {
            return;
        }
        try {
            input.write(line);
            input.write('\n');
            input.flush();
        } catch (IOException e) {
            closed = true;
            log.accept("Early loading window stopped and will show nothing more " + "(" + e + ")");
        }
    }

    private void destroy() {
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    private static void forwardOutput(Process process, Consumer<String> log) {
        Thread reader = new Thread(
                () -> {
                    try (BufferedReader output = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = output.readLine()) != null) {
                            log.accept("Early window: " + line);
                        }
                    } catch (IOException e) {
                        // The process ended; its output with it.
                    }
                },
                "Plexus early window output");
        reader.setDaemon(true);
        reader.start();
    }

    /** The Java that runs this process; on Windows its {@code javaw.exe}, which opens no console window. */
    static Optional<Path> javaCommand() {
        return javaCommand(
                Path.of(System.getProperty("java.home")),
                ProcessHandle.current().info().command().map(Path::of).filter(Files::isRegularFile),
                Platform.current());
    }

    static Optional<Path> javaCommand(Path javaHome, Optional<Path> running, Platform platform) {
        Path bin = running.map(Path::getParent).orElse(javaHome.resolve("bin"));
        if (platform == Platform.WINDOWS) {
            Path javaw = bin.resolve("javaw.exe");
            if (Files.isRegularFile(javaw)) {
                return Optional.of(javaw);
            }
        }
        Path java = bin.resolve(platform == Platform.WINDOWS ? "java.exe" : "java");
        return Files.isRegularFile(java) ? Optional.of(java) : running;
    }

    /**
     * Where the window's classes and images are: this module's jar, or, in a development run, its class and
     * resource folders.
     */
    static Optional<String> classPath() {
        Set<Path> roots = new LinkedHashSet<>();
        root(EarlyWindowMain.class.getResource("EarlyWindowMain.class"), "EarlyWindowMain.class")
                .ifPresent(roots::add);
        root(EarlyWindowMain.class.getResource("plexus_cube.png"), "plexus_cube.png")
                .ifPresent(roots::add);
        if (roots.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join(
                java.io.File.pathSeparator, roots.stream().map(Path::toString).toList()));
    }

    // The folder or jar a resource of this package comes from.
    private static Optional<Path> root(URL url, String name) {
        if (url == null) {
            return Optional.empty();
        }
        try {
            String text = url.toString();
            if (text.startsWith("jar:file:")) {
                int bang = text.indexOf("!/");
                return Optional.of(Path.of(URI.create(text.substring(4, bang))));
            }
            if (text.startsWith("file:")) {
                Path file = Path.of(url.toURI());
                int depth = EarlyWindowMain.class.getPackageName().split("\\.").length;
                Path root = file.getParent();
                for (int i = 0; i < depth; i++) {
                    root = root.getParent();
                }
                return Optional.of(root);
            }
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
