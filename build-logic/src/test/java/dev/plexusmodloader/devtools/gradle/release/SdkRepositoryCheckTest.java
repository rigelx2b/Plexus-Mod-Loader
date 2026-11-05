package dev.plexusmodloader.devtools.gradle.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SdkRepositoryCheckTest {

    private static final String VERSION = "1.0.0";
    private static final List<SdkRepositoryCheck.Artifact> EXPECTED = List.of(
            new SdkRepositoryCheck.Artifact("dev.plexusmodloader", "plexus-api", "jar", true),
            new SdkRepositoryCheck.Artifact("dev.plexusmodloader", "plexus-impl", "jar", false));

    @TempDir
    Path repository;

    @Test
    void completeRepositoryHasNoProblems() throws IOException {
        publish("plexus-api", List.of(), true, "dev/plexusmodloader/Api.class");
        publish("plexus-impl", List.of("plexus-api"), false, "dev/plexusmodloader/Impl.class");

        assertEquals(List.of(), SdkRepositoryCheck.problems(repository, VERSION, EXPECTED));
    }

    @Test
    void sourcesMissingDependenciesAndStrayArtifactsAreReported() throws IOException {
        publish("plexus-api", List.of(), false, "dev/plexusmodloader/Api.java");
        publish("plexus-impl", List.of("plexus-api", "test-mod"), false, "dev/plexusmodloader/Impl.class");
        publish("test-mod", List.of(), false, "Test.class");
        Files.write(dir("plexus-impl").resolve("plexus-impl-" + VERSION + "-sources.jar"), new byte[0]);
        Files.writeString(
                dir("plexus-impl").resolve("plexus-impl-" + VERSION + ".module"),
                "{\"variants\": [{\"name\": \"apiElementsClasses\"}]}");

        String problems = String.join("\n", SdkRepositoryCheck.problems(repository, VERSION, EXPECTED));

        assertTrue(problems.contains("plexus-api-1.0.0.jar contains the Java source dev/plexusmodloader/Api.java"), problems);
        assertTrue(problems.contains("plexus-api:1.0.0: the JavaDoc jar is missing"), problems);
        assertTrue(problems.contains("depends on dev.plexusmodloader:test-mod, which is not part of the SDK"), problems);
        assertTrue(problems.contains("dev.plexusmodloader:test-mod:1.0.0 is in the repository but not part of the SDK"));
        assertTrue(problems.contains("plexus-impl-1.0.0-sources.jar: source jars are not published"), problems);
        assertTrue(problems.contains("the variant apiElementsClasses in plexus-impl-1.0.0.module has no files"), problems);
    }

    @Test
    void aJarWithoutTheLicenseAndNoticesIsReported() throws IOException {
        publish("plexus-api", List.of(), true, "dev/plexusmodloader/Api.class");
        publish("plexus-impl", List.of("plexus-api"), false, "dev/plexusmodloader/Impl.class");
        archive(dir("plexus-impl").resolve("plexus-impl-" + VERSION + ".jar"), "dev/plexusmodloader/Impl.class");

        List<String> problems = SdkRepositoryCheck.problems(repository, VERSION, EXPECTED);

        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("plexus-impl-1.0.0.jar has no META-INF/LICENSE"), problems.toString());
        assertTrue(problems.get(1).contains("has no META-INF/THIRD_PARTY_NOTICES.md"), problems.toString());
    }

    @Test
    void earlierVersionsOfTheFormerGroupAreLeftAlone() throws IOException {
        publish("plexus-api", List.of(), true, "dev/plexusmodloader/Api.class");
        publish("plexus-impl", List.of("plexus-api"), false, "dev/plexusmodloader/Impl.class");
        Path former = repository.resolve("dev/polymodloader/plexus-runtime-impl/0.9.0");
        Files.createDirectories(former);
        Files.writeString(former.resolve("plexus-runtime-impl-0.9.0.pom"), "<project/>");

        assertEquals(List.of(), SdkRepositoryCheck.problems(repository, VERSION, EXPECTED));
    }

    @Test
    void aNewArtifactUnderTheFormerGroupOrPluginIdIsReported() throws IOException {
        publish("plexus-api", List.of(), true, "dev/plexusmodloader/Api.class");
        publish("plexus-impl", List.of("plexus-api"), false, "dev/plexusmodloader/Impl.class");
        Path runtime = repository.resolve("dev/polymodloader/plexus-runtime-impl").resolve(VERSION);
        Files.createDirectories(runtime);
        Files.writeString(runtime.resolve("plexus-runtime-impl-" + VERSION + ".pom"), "<project/>");
        Path marker = repository.resolve("dev/polymodloader/mod/dev.polymodloader.mod.gradle.plugin").resolve(VERSION);
        Files.createDirectories(marker);
        Files.writeString(marker.resolve("dev.polymodloader.mod.gradle.plugin-" + VERSION + ".pom"), "<project/>");

        String problems = String.join("\n", SdkRepositoryCheck.problems(repository, VERSION, EXPECTED));

        assertTrue(
                problems.contains("dev.polymodloader:plexus-runtime-impl:1.0.0 is published under the former group"),
                problems);
        assertTrue(problems.contains("dev.polymodloader.mod:dev.polymodloader.mod.gradle.plugin:1.0.0"), problems);
    }

    @Test
    void missingRepositoryIsReported() {
        assertEquals(1, SdkRepositoryCheck.problems(repository.resolve("none"), VERSION, EXPECTED).size());
    }

    private Path dir(String artifact) {
        return repository.resolve("dev/plexusmodloader").resolve(artifact).resolve(VERSION);
    }

    private void publish(String artifact, List<String> dependencies, boolean javadoc, String entry)
            throws IOException {
        Path directory = dir(artifact);
        Files.createDirectories(directory);
        StringBuilder pom = new StringBuilder("<project><groupId>dev.plexusmodloader</groupId><dependencies>");
        for (String dependency : dependencies) {
            pom.append("<dependency><groupId>dev.plexusmodloader</groupId><artifactId>")
                    .append(dependency)
                    .append("</artifactId><version>")
                    .append(VERSION)
                    .append("</version></dependency>");
        }
        pom.append("</dependencies></project>");
        Files.writeString(directory.resolve(artifact + "-" + VERSION + ".pom"), pom, StandardCharsets.UTF_8);
        jar(directory.resolve(artifact + "-" + VERSION + ".jar"), entry);
        if (javadoc) {
            jar(directory.resolve(artifact + "-" + VERSION + "-javadoc.jar"), "index.html");
        }
        Files.writeString(
                directory.getParent().resolve("maven-metadata.xml"),
                "<metadata><versioning><versions><version>" + VERSION + "</version></versions></versioning></metadata>");
    }

    private static void jar(Path file, String entry) throws IOException {
        archive(file, entry, "META-INF/LICENSE", "META-INF/THIRD_PARTY_NOTICES.md");
    }

    private static void archive(Path file, String... entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(1);
                zip.closeEntry();
            }
        }
    }
}
