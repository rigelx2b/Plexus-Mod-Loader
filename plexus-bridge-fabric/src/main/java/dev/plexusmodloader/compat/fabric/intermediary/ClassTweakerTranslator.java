package dev.plexusmodloader.compat.fabric.intermediary;

import java.util.Optional;

/**
 * Rewrites a Fabric class tweaker (access widener) file that names the game's classes in the {@code intermediary}
 * namespace so that it names them in {@code official}, the namespace the remapped game has (ADR-0018, ADR-0037): class
 * names, member names and descriptors are translated and the header names the new namespace. The file otherwise keeps
 * its lines, order and comments.
 *
 * <p>A file in another namespace, or one whose first line is not a three-word header, is not touched; {@code
 * ClassTweakerCheck} then reports it as before ({@code PLEXUS-COMPAT-125}, {@code -126}).
 */
final class ClassTweakerTranslator {

    static final String FROM = "intermediary";
    static final String TO = "official";

    private ClassTweakerTranslator() {}

    /** The translated file, or empty when it is not an {@code intermediary} class tweaker. */
    static Optional<String> translate(String text, IntermediaryTranslation translation) {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0) {
            return Optional.empty();
        }
        String header = stripCarriageReturn(lines[0]);
        String[] words = header.trim().split("\\s+");
        if (words.length != 3
                || !(words[0].equals("accessWidener") || words[0].equals("classTweaker"))
                || !words[2].equals(FROM)) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder(text.length());
        int namespaceAt = header.lastIndexOf(FROM);
        out.append(header, 0, namespaceAt)
                .append(TO)
                .append(header.substring(namespaceAt + FROM.length()))
                .append(lines[0].endsWith("\r") ? "\r" : "");
        for (int i = 1; i < lines.length; i++) {
            out.append('\n').append(translateLine(lines[i], translation));
        }
        return Optional.of(out.toString());
    }

    private static String translateLine(String raw, IntermediaryTranslation translation) {
        boolean carriageReturn = raw.endsWith("\r");
        String line = stripCarriageReturn(raw);
        int hash = line.indexOf('#');
        String body = hash < 0 ? line : line.substring(0, hash);
        String comment = hash < 0 ? "" : line.substring(hash);
        String result = line;
        if (!body.isBlank()) {
            String[] words = body.trim().split("[ \\t]+");
            String directive =
                    words[0].startsWith("transitive-") ? words[0].substring("transitive-".length()) : words[0];
            boolean changed = false;
            switch (directive.toLowerCase(java.util.Locale.ROOT)) {
                case "accessible", "extendable", "mutable" -> {
                    changed = access(words, translation);
                }
                case "inject-interface" -> {
                    if (words.length >= 3) {
                        String target = translation.mapClass(words[1]);
                        String api = translation.mapText(words[2]);
                        changed = !target.equals(words[1]) || !api.equals(words[2]);
                        words[1] = target;
                        words[2] = api;
                    }
                }
                case "extend-enum" -> {
                    if (words.length >= 2) {
                        String target = translation.mapClass(words[1]);
                        changed = !target.equals(words[1]);
                        words[1] = target;
                    }
                }
                default -> {
                    // A line Fabric Loader would refuse; ClassTweakerCheck reports it.
                }
            }
            if (changed) {
                result = String.join("\t", words) + (comment.isEmpty() ? "" : " " + comment);
            }
        }
        return carriageReturn ? result + "\r" : result;
    }

    private static boolean access(String[] words, IntermediaryTranslation translation) {
        if (words.length < 3) {
            return false;
        }
        String owner = words[2];
        String mappedOwner = translation.mapClass(owner);
        boolean changed = !mappedOwner.equals(owner);
        words[2] = mappedOwner;
        if (words[1].equals("method") && words.length >= 5) {
            String name = translation.mapMethod(owner, words[3], words[4]);
            String descriptor = translation.mapText(words[4]);
            changed |= !name.equals(words[3]) || !descriptor.equals(words[4]);
            words[3] = name;
            words[4] = descriptor;
        } else if (words[1].equals("field") && words.length >= 5) {
            String name = translation.mapField(owner, words[3], words[4]);
            String descriptor = translation.mapText(words[4]);
            changed |= !name.equals(words[3]) || !descriptor.equals(words[4]);
            words[3] = name;
            words[4] = descriptor;
        }
        return changed;
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
