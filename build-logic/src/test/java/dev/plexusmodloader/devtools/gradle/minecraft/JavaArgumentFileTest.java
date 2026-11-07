package dev.plexusmodloader.devtools.gradle.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaArgumentFileTest {

    @TempDir
    Path folder;

    /**
     * corpusQuiltClient on 26.2 failed with CreateProcess error=206: its command line was over Windows' limit. The same
     * arguments, longer than that limit and with spaces, backslashes and quotes, reach the JVM through the file.
     */
    @Test
    void theJvmReadsEveryArgumentFromTheFileWhateverItsLength() throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String value = "C:\\Program Files\\a b\\\"quoted\"\\" + "x".repeat(40_000);
        List<String> command = new ArrayList<>(List.of(java, "-Dplexus.argfile.test=" + value, "-XshowSettings:properties", "-version"));

        List<String> started = JavaArgumentFile.commandWithArgumentFile(command, folder.resolve("java.args"));

        assertEquals(2, started.size());
        assertTrue(started.get(1).startsWith("@"));
        Process process = new ProcessBuilder(started).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("plexus.argfile.test = " + value), "the property did not arrive as written");
    }

    /**
     * corpusQuiltForge on 1.20.1 failed with CreateProcess error=206: a JavaExec task's JVM arguments (plexus.devModJars
     * with about 250 jars) were over the limit. Long ones go to a file the launcher expands among the JVM options; short
     * ones stay as they are.
     */
    @Test
    void longJvmArgumentsGoToAFileTheLauncherExpandsBeforeTheMainClass() throws IOException, InterruptedException {
        List<String> shortArguments = List.of("-Dplexus.short=1");
        assertEquals(shortArguments, JavaArgumentFile.inFile(shortArguments, folder.resolve("short.args")));

        String value = "C:\\mods\\a b.jar;" + "y".repeat(40_000);
        List<String> moved = JavaArgumentFile.inFile(
                List.of("-Dplexus.argfile.test=" + value, "-Dplexus.second=2"), folder.resolve("jvm.args"));
        assertEquals(1, moved.size());
        assertTrue(moved.getFirst().startsWith("@"));

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(java));
        command.addAll(moved);
        command.addAll(List.of("-XshowSettings:properties", "-version"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("plexus.argfile.test = " + value), "the property did not arrive as written");
        assertTrue(output.contains("plexus.second = 2"));
    }
}
