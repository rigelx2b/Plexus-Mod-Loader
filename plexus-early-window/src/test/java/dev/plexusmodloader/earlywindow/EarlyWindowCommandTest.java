package dev.plexusmodloader.earlywindow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AWTError;
import java.awt.HeadlessException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EarlyWindowCommandTest {

    private static final Path FAKE_JAVA = Path.of("C:\\Java\\bin\\javaw.exe");
    private static final String FAKE_CLASSPATH = "plexus-early-window.jar";
    private static final EarlyWindowSettings SETTINGS =
            new EarlyWindowSettings("Minecraft* Plexus 26.3", 854, 480, 2, false, Optional.empty(), Optional.empty());

    @Test
    void platformDetectionFromOsName() {
        assertEquals(Platform.WINDOWS, Platform.fromOsName("Windows 11"));
        assertEquals(Platform.WINDOWS, Platform.fromOsName("Windows Server 2022"));
        assertEquals(Platform.MAC, Platform.fromOsName("Mac OS X"));
        assertEquals(Platform.MAC, Platform.fromOsName("macOS"));
        assertEquals(Platform.MAC, Platform.fromOsName("Darwin"));
        assertEquals(Platform.OTHER, Platform.fromOsName("Linux"));
        assertEquals(Platform.OTHER, Platform.fromOsName("FreeBSD"));
    }

    @Test
    void windowsCommandLineByteForByteIdenticalToBaseline() {
        List<String> baseline = new ArrayList<>(List.of(
                FAKE_JAVA.toString(),
                "-Xms8m",
                "-Xmx64m",
                "-XX:+UseSerialGC",
                "-XX:TieredStopAtLevel=1",
                "-Djava.awt.headless=false",
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-Dsun.java2d.uiScale=1",
                "-cp",
                FAKE_CLASSPATH,
                EarlyWindowMain.class.getName()));
        baseline.addAll(SETTINGS.toArguments());

        List<String> actual = EarlyWindow.buildCommandLine(FAKE_JAVA, FAKE_CLASSPATH, SETTINGS, Platform.WINDOWS);
        assertEquals(baseline, actual);
    }

    @Test
    void linuxCommandLineByteForByteIdenticalToBaseline() {
        List<String> baseline = new ArrayList<>(List.of(
                FAKE_JAVA.toString(),
                "-Xms8m",
                "-Xmx64m",
                "-XX:+UseSerialGC",
                "-XX:TieredStopAtLevel=1",
                "-Djava.awt.headless=false",
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-Dsun.java2d.uiScale=1",
                "-cp",
                FAKE_CLASSPATH,
                EarlyWindowMain.class.getName()));
        baseline.addAll(SETTINGS.toArguments());

        List<String> actual = EarlyWindow.buildCommandLine(FAKE_JAVA, FAKE_CLASSPATH, SETTINGS, Platform.OTHER);
        assertEquals(baseline, actual);
    }

    @Test
    void macCommandLineHasUIElementNoUiScaleNoStartOnFirstThread() {
        List<String> command = EarlyWindow.buildCommandLine(FAKE_JAVA, FAKE_CLASSPATH, SETTINGS, Platform.MAC);

        assertTrue(command.contains("-Dapple.awt.UIElement=true"), "macOS must have UIElement property");
        assertFalse(command.contains("-Dsun.java2d.uiScale=1"), "macOS must not set sun.java2d.uiScale");
        assertFalse(command.contains("-XstartOnFirstThread"), "must never pass -XstartOnFirstThread to child AWT JVM");
        assertTrue(command.contains("-Djava.awt.headless=false"), "must keep java.awt.headless=false");
        assertTrue(command.contains("-Xms8m"));
        assertTrue(command.contains("-Xmx64m"));
        assertTrue(command.contains("-XX:+UseSerialGC"));
        assertTrue(command.contains("-XX:TieredStopAtLevel=1"));
        assertTrue(command.contains("-cp"));
        assertTrue(command.contains(FAKE_CLASSPATH));
        assertTrue(command.contains(EarlyWindowMain.class.getName()));
    }

    @Test
    void environmentSanitizingRemovesStartOnFirstThreadAndDropsBlankVars() {
        Map<String, String> env = Map.of(
                "JAVA_TOOL_OPTIONS", "-XstartOnFirstThread",
                "JDK_JAVA_OPTIONS", "   -XstartOnFirstThread   ",
                "_JAVA_OPTIONS", "-XstartOnFirstThread -Dfoo=bar",
                "PATH", "C:\\bin;-XstartOnFirstThread",
                "CUSTOM_VAR", "-XstartOnFirstThread");

        Map<String, String> sanitized = EarlyWindow.sanitizeEnvironment(env);

        assertFalse(sanitized.containsKey("JAVA_TOOL_OPTIONS"), "Blank JAVA_TOOL_OPTIONS must be dropped");
        assertFalse(sanitized.containsKey("JDK_JAVA_OPTIONS"), "Blank JDK_JAVA_OPTIONS must be dropped");
        assertEquals("-Dfoo=bar", sanitized.get("_JAVA_OPTIONS"));
        assertEquals("C:\\bin;-XstartOnFirstThread", sanitized.get("PATH"), "Unrelated variables must stay untouched");
        assertEquals("-XstartOnFirstThread", sanitized.get("CUSTOM_VAR"), "Unrelated variables must stay untouched");
    }

    @Test
    void environmentSanitizingHandlesMultipleTokensAndPositions() {
        assertEquals("-Dfoo=bar", EarlyWindow.sanitizeJvmOptions("-Dfoo=bar -XstartOnFirstThread"));
        assertEquals("-Dfoo=bar", EarlyWindow.sanitizeJvmOptions("-XstartOnFirstThread -Dfoo=bar"));
        assertEquals("-Dfoo=1   -Dbar=2", EarlyWindow.sanitizeJvmOptions("-Dfoo=1   -XstartOnFirstThread   -Dbar=2"));
        assertEquals("-Dfoo=1 -Dbar=2", EarlyWindow.sanitizeJvmOptions("-Dfoo=1 -XstartOnFirstThread -Dbar=2"));
        assertEquals("", EarlyWindow.sanitizeJvmOptions("-XstartOnFirstThread -XstartOnFirstThread"));
        assertEquals("-Dfoo", EarlyWindow.sanitizeJvmOptions("-XstartOnFirstThread -Dfoo -XstartOnFirstThread"));

        // Must not strip tokens that merely start with or contain the string as a substring
        assertEquals("-XstartOnFirstThreadOther", EarlyWindow.sanitizeJvmOptions("-XstartOnFirstThreadOther"));
        assertEquals("-Dprop=-XstartOnFirstThread", EarlyWindow.sanitizeJvmOptions("-Dprop=-XstartOnFirstThread"));
    }

    @Test
    void environmentSanitizingCaseInsensitiveEnvNames() {
        Map<String, String> env = Map.of(
                "java_tool_options", "-XstartOnFirstThread",
                "jdk_java_options", "-XstartOnFirstThread -Dprop=1");

        Map<String, String> sanitized = EarlyWindow.sanitizeEnvironment(env);
        assertFalse(sanitized.containsKey("java_tool_options"));
        assertEquals("-Dprop=1", sanitized.get("jdk_java_options"));
    }

    @Test
    void applyEnvironmentModifiesTargetMapCorrectly() {
        Map<String, String> target = new HashMap<>();
        target.put("JAVA_TOOL_OPTIONS", "-XstartOnFirstThread");
        target.put("JDK_JAVA_OPTIONS", "-Dkeep=1 -XstartOnFirstThread");
        target.put("PATH", "/usr/bin");

        Map<String, String> desired = EarlyWindow.sanitizeEnvironment(target);
        EarlyWindow.applyEnvironment(target, desired);

        assertFalse(target.containsKey("JAVA_TOOL_OPTIONS"));
        assertEquals("-Dkeep=1", target.get("JDK_JAVA_OPTIONS"));
        assertEquals("/usr/bin", target.get("PATH"));
    }

    @Test
    void javaCommandResolutionForMacBundleAndWindows(@TempDir Path tempDir) throws IOException {
        // macOS bundle layout: <bundle>/Contents/Home/bin/java
        Path macHome = tempDir.resolve("Contents/Home");
        Path macBin = macHome.resolve("bin");
        Files.createDirectories(macBin);
        Path macJava = macBin.resolve("java");
        Files.createFile(macJava);

        Optional<Path> resolvedMac = EarlyWindow.javaCommand(macHome, Optional.empty(), Platform.MAC);
        assertTrue(resolvedMac.isPresent());
        assertEquals(macJava, resolvedMac.get());

        // When running path is supplied inside a bundle
        Optional<Path> resolvedRunning = EarlyWindow.javaCommand(macHome, Optional.of(macJava), Platform.MAC);
        assertTrue(resolvedRunning.isPresent());
        assertEquals(macJava, resolvedRunning.get());

        // Windows layout: javaw.exe preferred over java.exe
        Path winHome = tempDir.resolve("winJdk");
        Path winBin = winHome.resolve("bin");
        Files.createDirectories(winBin);
        Path winJavaw = winBin.resolve("javaw.exe");
        Path winJava = winBin.resolve("java.exe");
        Files.createFile(winJava);

        // Only java.exe exists
        Optional<Path> resolvedWin1 = EarlyWindow.javaCommand(winHome, Optional.empty(), Platform.WINDOWS);
        assertEquals(Optional.of(winJava), resolvedWin1);

        // javaw.exe also exists -> javaw.exe selected
        Files.createFile(winJavaw);
        Optional<Path> resolvedWin2 = EarlyWindow.javaCommand(winHome, Optional.empty(), Platform.WINDOWS);
        assertEquals(Optional.of(winJavaw), resolvedWin2);
    }

    @Test
    void watchdogLogsPrematureExit() {
        TestProcess process = new TestProcess(1);
        List<String> log = new ArrayList<>();
        EarlyWindow window = new EarlyWindow(process, log::add, System.currentTimeMillis());
        window.watchStartup();
        process.finish();

        assertEquals(1, log.size(), "Watchdog should have logged premature exit");
        assertTrue(
                log.getFirst().contains("exited prematurely with code 1"),
                "Expected premature exit log, got: " + log.getFirst());
    }

    @Test
    void watchdogDoesNotLogWhenClosedNormally() {
        TestProcess process = new TestProcess(0);
        List<String> log = new ArrayList<>();
        EarlyWindow window = new EarlyWindow(process, log::add, System.currentTimeMillis());
        window.close(); // Closed normally before exit callback
        window.watchStartup();
        process.finish();

        assertTrue(log.isEmpty(), "Watchdog should not log after normal close: " + log);
    }

    @Test
    void watchdogDoesNotLogAfterTimeout() {
        TestProcess process = new TestProcess(1);
        List<String> log = new ArrayList<>();
        EarlyWindow window = new EarlyWindow(process, log::add, System.currentTimeMillis() - 10_000L);
        window.watchStartup();
        process.finish();

        assertTrue(log.isEmpty(), "Watchdog should not log when exiting after timeout");
    }

    @Test
    void childFallbackHandlesHeadlessAndAwtErrorsGracefully(@TempDir Path tempDir) {
        AtomicReference<JFrame> window = new AtomicReference<>();
        List<String> errors = new ArrayList<>();

        // 1. HeadlessException handled
        EarlyWindowMain.openWindowSafely(
                window,
                () -> {
                    throw new HeadlessException("No display device available");
                },
                errors::add);
        assertNull(window.get());
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("No display device available"));

        // 2. AWTError handled
        errors.clear();
        EarlyWindowMain.openWindowSafely(
                window,
                () -> {
                    throw new AWTError("Can't connect to window server");
                },
                errors::add);
        assertNull(window.get());
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("Can't connect to window server"));

        // 3. UnsatisfiedLinkError handled
        errors.clear();
        EarlyWindowMain.openWindowSafely(
                window,
                () -> {
                    throw new UnsatisfiedLinkError("no awt in java.library.path");
                },
                errors::add);
        assertNull(window.get());
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("no awt"));

        // 4. InternalError handled
        errors.clear();
        EarlyWindowMain.openWindowSafely(
                window,
                () -> {
                    throw new InternalError("AppKit initialization failed");
                },
                errors::add);
        assertNull(window.get());
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("AppKit initialization failed"));

        // 5. Non-AWT exception is NOT swallowed
        assertThrows(
                IllegalArgumentException.class,
                () -> EarlyWindowMain.openWindowSafely(
                        window,
                        () -> {
                            throw new IllegalArgumentException("Unexpected");
                        },
                        errors::add));

        // 6. Child honors snapshot even when window is null
        LoadingScreenPainter painter = new LoadingScreenPainter(null, EarlyWindowArt.cube(), false);
        Path snapshotFile = tempDir.resolve("fallback-snapshot.png");
        EarlyWindowMain.snapshot(SETTINGS, painter, LoadingScreenState.initial(), snapshotFile.toString());
        assertTrue(Files.isRegularFile(snapshotFile), "Snapshot must be written even without an open window");

        // 7. Child honors closeWindow even when window is null
        EarlyWindowMain.closeWindow(window);
        assertNull(window.get());
    }

    private static final class TestProcess extends Process {
        private final CompletableFuture<Process> exitFuture = new CompletableFuture<>();
        private final int exitCode;

        TestProcess(int exitCode) {
            this.exitCode = exitCode;
        }

        void finish() {
            exitFuture.complete(this);
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return exitFuture;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public boolean isAlive() {
            return !exitFuture.isDone();
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public void destroy() {}
    }
}
