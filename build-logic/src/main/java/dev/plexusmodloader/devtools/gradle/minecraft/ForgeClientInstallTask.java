package dev.plexusmodloader.devtools.gradle.minecraft;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.work.DisableCachingByDefault;

/**
 * Installs MinecraftForge's client with Forge's own installer ({@code --installClient}) into a folder laid out like a
 * launcher's, for the client jar Forge's installer patched ({@link #patchedClientJar}), which holds the common classes
 * too: Plexus Mod Loader's MinecraftForge host modules compile against it (ADR-0031). The installer comes from Forge's
 * Maven and is checked against {@link #getInstallerSha1()}; it downloads Minecraft's client and Forge's libraries and
 * applies Forge's patches itself. A finished installation is reused while the installer is the same. Nothing is
 * started.
 */
@DisableCachingByDefault(because = "Runs an external installer that downloads from the network")
public abstract class ForgeClientInstallTask extends DefaultTask {

    /** The Forge version, such as {@code 26.2-65.1.5}. */
    @Input
    public abstract Property<String> getForgeVersion();

    /** SHA-1 of {@code forge-<version>-installer.jar} on Forge's Maven. */
    @Input
    public abstract Property<String> getInstallerSha1();

    @Nested
    public abstract Property<JavaLauncher> getJavaLauncher();

    /** The launcher-like folder: {@code launcher_profiles.json}, {@code libraries/}, {@code versions/}. */
    @Internal
    public abstract DirectoryProperty getClientDirectory();

    /** The patched client jar inside {@code client}, for Forge {@code version}: every game class, patched or not. */
    public static Path patchedClientJar(Path client, String version) {
        return client.resolve(
                "libraries/net/minecraftforge/forge/" + version + "/forge-" + version + "-client.jar");
    }

    @TaskAction
    public void install() throws IOException, InterruptedException {
        String version = getForgeVersion().get();
        String sha1 = getInstallerSha1().get();
        Path client = getClientDirectory().get().getAsFile().toPath();
        Path stamp = client.resolve(".plexus-installed");
        if (Files.isRegularFile(stamp)
                && Files.readString(stamp, StandardCharsets.UTF_8).strip().equals(sha1)
                && Files.isRegularFile(patchedClientJar(client, version))) {
            getLogger().lifecycle("MinecraftForge {} client already installed in {}", version, client);
            return;
        }
        VerifiedGameRunTask.deleteRecursively(client);
        Files.createDirectories(client);
        // The installer adds its profile to a launcher's profiles file and refuses a folder without one.
        Files.writeString(client.resolve("launcher_profiles.json"), "{\"profiles\":{}}\n", StandardCharsets.UTF_8);
        Path installer = client.getParent().resolve("forge-" + version + "-installer.jar");
        new VerifiedDownloader().downloadSha1Verified(installerUri(version), sha1, installer);
        List<String> command = List.of(
                getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath(),
                "-jar",
                installer.toString(),
                "--installClient",
                client.toString());
        Path log = client.getParent().resolve("forge-" + version + "-client-install.log");
        Process process = new ProcessBuilder(command)
                .directory(client.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(20, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new GradleException("MinecraftForge's installer timed out; see " + log);
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        if (process.exitValue() != 0 || !output.contains("Successfully installed client")) {
            throw new GradleException(
                    "MinecraftForge's installer failed (exit " + process.exitValue() + "); see " + log);
        }
        if (!Files.isRegularFile(patchedClientJar(client, version))) {
            throw new GradleException(
                    "MinecraftForge's installer wrote no " + patchedClientJar(client, version) + "; see " + log);
        }
        Files.writeString(stamp, sha1 + "\n", StandardCharsets.UTF_8);
        getLogger().lifecycle("Installed the MinecraftForge {} client in {}", version, client);
    }

    static URI installerUri(String version) {
        return URI.create("https://maven.minecraftforge.net/net/minecraftforge/forge/" + version + "/forge-"
                + version + "-installer.jar");
    }
}
