package dev.plexusmodloader.devtools.gradle.minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts {@code java} with its arguments in an argument file ({@code java @file}, JDK 9+) instead of on the command
 * line. Windows refuses a command line over 32,767 characters ({@code CreateProcess error=206}); a client with the
 * whole Quilt corpus in {@code plexus.devMods} and Minecraft's libraries on {@code -cp} goes over it.
 */
final class JavaArgumentFile {

    private JavaArgumentFile() {}

    /**
     * Writes every argument after {@code command[0]} (the {@code java} executable) to {@code file}, one per line,
     * quoted as the launcher reads argument files (backslash and double quote escaped), and returns {@code [java,
     * @file]}.
     */
    static List<String> commandWithArgumentFile(List<String> command, Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String argument : command.subList(1, command.size())) {
            lines.add(quote(argument));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
        return List.of(command.getFirst(), "@" + file.toAbsolutePath());
    }

    /** Above this many characters, {@link #inFile} moves JVM arguments to a file (Windows allows 32,767 in all). */
    static final int LONGEST_INLINE = 16_000;

    /**
     * {@code arguments} as they are, or, when they are longer than {@link #LONGEST_INLINE} characters together, written
     * to {@code file} and replaced by {@code @file}, which the {@code java} launcher expands wherever it stands among its
     * options (a {@code JavaExec} task's JVM arguments, before the main class).
     */
    static List<String> inFile(List<String> arguments, Path file) throws IOException {
        int length = arguments.stream().mapToInt(argument -> argument.length() + 1).sum();
        if (length <= LONGEST_INLINE) {
            return arguments;
        }
        List<String> lines = new ArrayList<>();
        for (String argument : arguments) {
            lines.add(quote(argument));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
        return List.of("@" + file.toAbsolutePath());
    }

    static String quote(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
