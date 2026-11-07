package dev.plexusmodloader.api.config;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * A typed, commented config file of a mod: the values a mod reads, their defaults and limits, and the
 * text file they live in.
 *
 * <p>A mod builds one spec while it initialises, keeps the {@link Value} objects it gets from the
 * builder, and calls {@link #load(Path)} with a file in {@code ModContext.configDirectory()}. The file uses
 * the {@code key=value} text format of Java properties, one entry per value with its comment above. A missing
 * file is written with the defaults. A value that is missing, of the wrong type, or outside its limits falls back
 * to its default, and the file is rewritten with the corrected entries so that it never keeps text the mod
 * ignores; every correction is returned as a warning. Keys the spec does not know are dropped when the file is
 * rewritten.
 *
 * <p>Threads: {@link Value#get()} may be called from any thread and returns the value of the last completed
 * load. {@code load} and {@code save} should be called from one thread at a time (the mod's own).
 * Failure: an unreadable or unwritable file throws {@link IOException}; the values keep the previous state.
 * Not provided: synchronising the file to clients, and a config screen. A mod that needs a value on the
 * client sends it over a {@code PayloadChannel}.
 */
@ApiStability(StabilityLevel.PREVIEW)
public final class ConfigSpec {

    private final List<Entry<?>> entries;

    private ConfigSpec(List<Entry<?>> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Starts a spec. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads {@code file} into the values and rewrites the file when something was missing or had to be corrected.
     * A missing file is created, with its parent folders.
     *
     * @param file the config file, normally {@code mod.configDirectory().resolve("name.properties")}
     * @return what was corrected; empty when the file was read as it stood
     * @throws IOException when the file cannot be read or written
     */
    public synchronized List<String> load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Properties properties = new Properties();
        boolean existed = Files.exists(file);
        if (existed) {
            properties.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        }
        List<String> warnings = new ArrayList<>();
        boolean rewrite = !existed;
        for (Entry<?> entry : entries) {
            String text = properties.getProperty(entry.key);
            if (text == null) {
                entry.reset();
                if (existed) {
                    warnings.add(entry.key + " is missing; using the default " + entry.formatDefault());
                    rewrite = true;
                }
                continue;
            }
            String problem = entry.parse(text);
            if (problem != null) {
                warnings.add(entry.key + " " + problem + "; using the default " + entry.formatDefault());
                entry.reset();
                rewrite = true;
            }
        }
        if (existed) {
            for (String key : properties.stringPropertyNames()) {
                if (entries.stream().noneMatch(entry -> entry.key.equals(key))) {
                    warnings.add(key + " is not a known setting and is dropped");
                    rewrite = true;
                }
            }
        }
        if (rewrite) {
            save(file);
        }
        return Collections.unmodifiableList(warnings);
    }

    /**
     * Writes the current values with their comments to {@code file}, creating parent folders.
     *
     * @throws IOException when the file cannot be written
     */
    public synchronized void save(Path file) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Entry<?> entry : entries) {
            if (!entry.comment.isEmpty()) {
                for (String line : entry.comment.split("\n", -1)) {
                    text.append("# ").append(line).append('\n');
                }
            }
            text.append("# ").append(entry.describe()).append('\n');
            text.append(entry.key)
                    .append('=')
                    .append(escape(entry.formatCurrent()))
                    .append("\n\n");
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    /** The keys of all values, in declaration order. */
    public List<String> keys() {
        return entries.stream().map(entry -> entry.key).toList();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case ' ' -> out.append(i == 0 ? "\\ " : " ");
                case '#', '!', '=', ':' -> out.append('\\').append(c);
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** One value of a spec. */
    @ApiStability(StabilityLevel.PREVIEW)
    public interface Value<T> {

        /** The key in the file. */
        String key();

        /** The value from the last {@link ConfigSpec#load(Path)}, or the default before the first load. */
        T get();

        /** The value used when the file has none or an invalid one. */
        T defaultValue();
    }

    private abstract static class Entry<T> implements Value<T> {
        final String key;
        final T defaultValue;
        final String comment;
        volatile T value;

        Entry(String key, T defaultValue, String comment) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.comment = comment;
            this.value = defaultValue;
        }

        void reset() {
            value = defaultValue;
        }

        /** Returns null when {@code text} was accepted, otherwise why not. */
        abstract String parse(String text);

        String format(T v) {
            return String.valueOf(v);
        }

        final String formatDefault() {
            return format(defaultValue);
        }

        final String formatCurrent() {
            return format(value);
        }

        String describe() {
            return "default: " + format(defaultValue);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public T defaultValue() {
            return defaultValue;
        }
    }

    private static final class BooleanEntry extends Entry<Boolean> {
        BooleanEntry(String key, boolean defaultValue, String comment) {
            super(key, defaultValue, comment);
        }

        @Override
        String parse(String raw) {
            String text = raw.trim();
            if (text.equalsIgnoreCase("true")) {
                value = true;
                return null;
            }
            if (text.equalsIgnoreCase("false")) {
                value = false;
                return null;
            }
            return "must be true or false, not '" + text + "'";
        }
    }

    private static final class IntEntry extends Entry<Integer> {
        final int min;
        final int max;

        IntEntry(String key, int defaultValue, int min, int max, String comment) {
            super(key, defaultValue, comment);
            this.min = min;
            this.max = max;
        }

        @Override
        String parse(String raw) {
            String text = raw.trim();
            int parsed;
            try {
                parsed = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return "must be a whole number, not '" + text + "'";
            }
            if (parsed < min || parsed > max) {
                return "must be between " + min + " and " + max + ", not " + parsed;
            }
            value = parsed;
            return null;
        }

        @Override
        String describe() {
            return "default: " + defaultValue + ", range: " + min + " to " + max;
        }
    }

    private static final class DoubleEntry extends Entry<Double> {
        final double min;
        final double max;

        DoubleEntry(String key, double defaultValue, double min, double max, String comment) {
            super(key, defaultValue, comment);
            this.min = min;
            this.max = max;
        }

        @Override
        String parse(String raw) {
            String text = raw.trim();
            double parsed;
            try {
                parsed = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return "must be a number, not '" + text + "'";
            }
            if (Double.isNaN(parsed) || parsed < min || parsed > max) {
                return "must be between " + min + " and " + max + ", not " + text;
            }
            value = parsed;
            return null;
        }

        @Override
        String describe() {
            return "default: " + defaultValue + ", range: " + min + " to " + max;
        }
    }

    private static final class StringEntry extends Entry<String> {
        StringEntry(String key, String defaultValue, String comment) {
            super(key, defaultValue, comment);
        }

        @Override
        String parse(String text) {
            value = text;
            return null;
        }
    }

    private static final class EnumEntry<E extends Enum<E>> extends Entry<E> {
        final Class<E> type;

        EnumEntry(String key, E defaultValue, String comment) {
            super(key, defaultValue, comment);
            this.type = defaultValue.getDeclaringClass();
        }

        @Override
        String parse(String raw) {
            String text = raw.trim();
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(text)) {
                    value = constant;
                    return null;
                }
            }
            return "must be one of " + options() + ", not '" + text + "'";
        }

        @Override
        String format(E v) {
            return v.name();
        }

        @Override
        String describe() {
            return "default: " + defaultValue.name() + ", one of: " + options();
        }

        private String options() {
            List<String> names = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                names.add(constant.name());
            }
            return String.join(", ", names);
        }
    }

    /** Builds a {@link ConfigSpec}. Not thread-safe. */
    @ApiStability(StabilityLevel.PREVIEW)
    public static final class Builder {

        private final Map<String, Entry<?>> entries = new LinkedHashMap<>();
        private String pendingComment = "";

        private Builder() {}

        /** Sets the comment written above the next value; it is used once. */
        public Builder comment(String comment) {
            this.pendingComment = Objects.requireNonNull(comment, "comment");
            return this;
        }

        /** Adds a true/false value. */
        public Value<Boolean> booleanValue(String key, boolean defaultValue) {
            return add(new BooleanEntry(checkKey(key), defaultValue, takeComment()));
        }

        /** Adds a whole number from {@code min} to {@code max}, both included. */
        public Value<Integer> intValue(String key, int defaultValue, int min, int max) {
            if (min > max || defaultValue < min || defaultValue > max) {
                throw new IllegalArgumentException("Invalid range or default for " + key);
            }
            return add(new IntEntry(checkKey(key), defaultValue, min, max, takeComment()));
        }

        /** Adds a number from {@code min} to {@code max}, both included. */
        public Value<Double> doubleValue(String key, double defaultValue, double min, double max) {
            if (!(min <= max) || !(defaultValue >= min && defaultValue <= max)) {
                throw new IllegalArgumentException("Invalid range or default for " + key);
            }
            return add(new DoubleEntry(checkKey(key), defaultValue, min, max, takeComment()));
        }

        /** Adds a text value. */
        public Value<String> stringValue(String key, String defaultValue) {
            return add(new StringEntry(
                    checkKey(key), Objects.requireNonNull(defaultValue, "defaultValue"), takeComment()));
        }

        /** Adds a value that is one constant of an enum; the file accepts the names in any letter case. */
        public <E extends Enum<E>> Value<E> enumValue(String key, E defaultValue) {
            return add(new EnumEntry<>(
                    checkKey(key), Objects.requireNonNull(defaultValue, "defaultValue"), takeComment()));
        }

        /** Finishes the spec. */
        public ConfigSpec build() {
            return new ConfigSpec(new ArrayList<>(entries.values()));
        }

        private <T> Value<T> add(Entry<T> entry) {
            if (entries.putIfAbsent(entry.key, entry) != null) {
                throw new IllegalArgumentException("Duplicate config key " + entry.key);
            }
            return entry;
        }

        private String takeComment() {
            String comment = pendingComment;
            pendingComment = "";
            return comment;
        }

        private static String checkKey(String key) {
            Objects.requireNonNull(key, "key");
            if (key.isEmpty() || !key.matches("[A-Za-z0-9_.\\-]+")) {
                throw new IllegalArgumentException("A config key uses letters, digits, '_', '.' and '-': " + key);
            }
            return key;
        }
    }
}
