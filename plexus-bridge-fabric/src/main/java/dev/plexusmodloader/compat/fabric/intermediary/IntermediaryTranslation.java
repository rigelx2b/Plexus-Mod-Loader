package dev.plexusmodloader.compat.fabric.intermediary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates Fabric's intermediary names (what a Fabric mod for Minecraft before 26.1 is built against) to the Mojang
 * names the remapped game jar has (ADR-0019, ADR-0037): intermediary to the obfuscated name through FabricMC's mappings
 * ({@link IntermediaryMappings}), obfuscated to Mojang through Mojang's mappings ({@link MojangNames}). A name that
 * Mojang's mappings do not list keeps its obfuscated name, which is the name the remapped jar gives it too.
 *
 * <p>Classes are mapped by name. Members are mapped by the class a reference names, then by the member's intermediary
 * name and descriptor, which identify a method uniquely. (Fabric could reuse a number for members of unrelated
 * classes; none of the four obfuscated versions the project supports has such a case, measured on their real
 * mappings, but a later version might.) A reused number is resolved through the class hierarchy when {@link
 * #withParents} gave one, and is otherwise left alone and counted ({@link #unresolved}).
 *
 * <p>Thread: safe from any thread after construction; the counters are atomic.
 */
public final class IntermediaryTranslation {

    /** The superclass and interfaces of a class, by internal name in intermediary terms; empty for unknown classes. */
    @FunctionalInterface
    public interface Parents {
        List<String> of(String intermediaryClass);
    }

    private static final String AMBIGUOUS = "\0ambiguous";
    private static final int MAX_DEPTH = 64;
    private static final Pattern MEMBER_REF = Pattern.compile("(?<owner>L[A-Za-z0-9_$/]+;)?"
            + "(?<name>(?:method|field|comp)_[0-9]+)"
            + "(?<desc>\\([^)]*\\)\\[*(?:[BCDFIJSZV]|L[^;]+;)|:\\[*(?:[BCDFIJSZ]|L[^;]+;))?");
    private static final Pattern CLASS_TOKEN = Pattern.compile("[A-Za-z0-9_$]+(?:[/.][A-Za-z0-9_$]+)*");
    private static final Pattern CLASS_NUMBER = Pattern.compile("class_[0-9]");
    private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L([^;]+);");

    /** Intermediary internal class name to the Mojang one, for classes whose name differs. */
    private final Map<String, String> classes;
    /** Per intermediary class: {@code name + descriptor} to the Mojang method name. */
    private final Map<String, Map<String, String>> methodsByOwner;
    /** {@code name + descriptor} to the Mojang method name, or {@link #AMBIGUOUS}. */
    private final Map<String, String> methodsByNameAndDescriptor;
    /** Intermediary method name to the Mojang name, or {@link #AMBIGUOUS}. */
    private final Map<String, String> methodsByName;

    /** Intermediary method name to its descriptor, or {@link #AMBIGUOUS} when classes declare it with several. */
    private final Map<String, String> methodDescriptorByName;

    private final Map<String, Map<String, String>> fieldsByOwner;
    private final Map<String, String> fieldsByNameAndDescriptor;
    private final Map<String, String> fieldsByName;

    private final Parents parents;
    private final AtomicInteger unresolved = new AtomicInteger();
    private final Set<String> unresolvedExamples = Collections.synchronizedSet(new TreeSet<>());

    private IntermediaryTranslation() {
        this.classes = new HashMap<>();
        this.methodsByOwner = new HashMap<>();
        this.methodsByNameAndDescriptor = new HashMap<>();
        this.methodsByName = new HashMap<>();
        this.methodDescriptorByName = new HashMap<>();
        this.fieldsByOwner = new HashMap<>();
        this.fieldsByNameAndDescriptor = new HashMap<>();
        this.fieldsByName = new HashMap<>();
        this.parents = intermediaryClass -> List.of();
    }

    /** The same tables with another hierarchy and fresh counters. */
    private IntermediaryTranslation(IntermediaryTranslation base, Parents parents) {
        this.classes = base.classes;
        this.methodsByOwner = base.methodsByOwner;
        this.methodsByNameAndDescriptor = base.methodsByNameAndDescriptor;
        this.methodsByName = base.methodsByName;
        this.methodDescriptorByName = base.methodDescriptorByName;
        this.fieldsByOwner = base.fieldsByOwner;
        this.fieldsByNameAndDescriptor = base.fieldsByNameAndDescriptor;
        this.fieldsByName = base.fieldsByName;
        this.parents = parents;
    }

    /** Combines both mapping files. */
    static IntermediaryTranslation of(IntermediaryMappings intermediary, MojangNames mojang) {
        IntermediaryTranslation translation = new IntermediaryTranslation();
        Map<String, String> officialToIntermediary = new HashMap<>();
        for (IntermediaryMappings.ClassEntry entry : intermediary.classes()) {
            officialToIntermediary.put(entry.official(), entry.intermediary());
        }
        for (IntermediaryMappings.ClassEntry entry : intermediary.classes()) {
            String mojangClass = mojang.className(entry.official());
            if (!mojangClass.equals(entry.intermediary())) {
                translation.classes.put(entry.intermediary(), mojangClass);
            }
            for (IntermediaryMappings.Member field : entry.fields()) {
                String name = mojang.fieldName(entry.official(), field.officialName());
                if (!name.equals(field.intermediaryName())) {
                    String descriptor = intermediaryDescriptor(field.officialDescriptor(), officialToIntermediary);
                    translation
                            .fieldsByOwner
                            .computeIfAbsent(entry.intermediary(), key -> new HashMap<>())
                            .put(field.intermediaryName(), name);
                    translation.fieldsByNameAndDescriptor.merge(
                            field.intermediaryName() + ":" + descriptor, name, IntermediaryTranslation::unique);
                    translation.fieldsByName.merge(field.intermediaryName(), name, IntermediaryTranslation::unique);
                }
            }
            for (IntermediaryMappings.Member method : entry.methods()) {
                String name = mojang.methodName(entry.official(), method.officialName(), method.officialDescriptor());
                if (!name.equals(method.intermediaryName())) {
                    String descriptor = intermediaryDescriptor(method.officialDescriptor(), officialToIntermediary);
                    translation
                            .methodsByOwner
                            .computeIfAbsent(entry.intermediary(), key -> new HashMap<>())
                            .put(method.intermediaryName() + descriptor, name);
                    translation.methodsByNameAndDescriptor.merge(
                            method.intermediaryName() + descriptor, name, IntermediaryTranslation::unique);
                    translation.methodsByName.merge(method.intermediaryName(), name, IntermediaryTranslation::unique);
                    translation.methodDescriptorByName.merge(
                            method.intermediaryName(), descriptor, IntermediaryTranslation::unique);
                }
            }
        }
        return translation;
    }

    private static String unique(String first, String second) {
        return first.equals(second) ? first : AMBIGUOUS;
    }

    private static String intermediaryDescriptor(String official, Map<String, String> officialToIntermediary) {
        if (official.indexOf('L') < 0) {
            return official;
        }
        Matcher matcher = DESCRIPTOR_CLASS.matcher(official);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            matcher.appendReplacement(
                    out, Matcher.quoteReplacement("L" + officialToIntermediary.getOrDefault(name, name) + ";"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The same translation with a class hierarchy, used for the few members whose intermediary name and descriptor
     * name different Mojang methods in unrelated classes. It shares the tables and counts its own unresolved names.
     */
    public IntermediaryTranslation withParents(Parents hierarchy) {
        return new IntermediaryTranslation(this, hierarchy);
    }

    /** The intermediary name of a Mojang class name; other names come back as they are. */
    public String unmapClass(String mojangInternalName) {
        Map<String, String> reverse = reverseClasses;
        if (reverse == null) {
            reverse = new HashMap<>(classes.size() * 2);
            for (Map.Entry<String, String> entry : classes.entrySet()) {
                reverse.put(entry.getValue(), entry.getKey());
            }
            reverseClasses = reverse;
        }
        String intermediary = reverse.get(mojangInternalName);
        return intermediary == null ? mojangInternalName : intermediary;
    }

    private volatile Map<String, String> reverseClasses;

    /** Number of classes whose name differs between the two namespaces. */
    int classCount() {
        return classes.size();
    }

    /** Number of method names that are the same intermediary name and descriptor in several classes with different Mojang names. */
    int ambiguousMethodCount() {
        return (int) methodsByNameAndDescriptor.values().stream()
                .filter(AMBIGUOUS::equals)
                .count();
    }

    /** Number of intermediary method names (any descriptor) that mean different Mojang names in different classes. */
    int ambiguousMethodNameCount() {
        return (int) methodsByName.values().stream().filter(AMBIGUOUS::equals).count();
    }

    /** Number of field names that are the same intermediary name and descriptor in several classes with different Mojang names. */
    int ambiguousFieldCount() {
        return (int) fieldsByNameAndDescriptor.values().stream()
                .filter(AMBIGUOUS::equals)
                .count();
    }

    /** Names it could not translate so far (ambiguous and no hierarchy decided, or a member the game does not have). */
    public int unresolved() {
        return unresolved.get();
    }

    /** Up to a few of the names behind {@link #unresolved}, sorted, for a message. */
    public List<String> unresolvedExamples() {
        synchronized (unresolvedExamples) {
            return new ArrayList<>(unresolvedExamples);
        }
    }

    /** The Mojang internal name of an intermediary class name; other names come back as they are. */
    public String mapClass(String internalName) {
        String mapped = classes.get(internalName);
        return mapped == null ? internalName : mapped;
    }

    /** Whether {@code name} is one of Fabric's numbered member names ({@code method_8320}, {@code field_9, comp_1}). */
    static boolean isIntermediaryMember(String name) {
        int underscore = name.indexOf('_');
        if (underscore < 4 || underscore == name.length() - 1) {
            return false;
        }
        String kind = name.substring(0, underscore);
        if (!kind.equals("method") && !kind.equals("field") && !kind.equals("comp")) {
            return false;
        }
        for (int i = underscore + 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The Mojang name of a method a class file references or declares.
     *
     * @param owner the class named by the reference or declaring the method, intermediary internal name
     * @param descriptor the method descriptor in intermediary terms
     */
    public String mapMethod(String owner, String name, String descriptor) {
        if (!isIntermediaryMember(name)) {
            return name;
        }
        Map<String, String> own = methodsByOwner.get(owner);
        String mapped = own == null ? null : own.get(name + descriptor);
        if (mapped != null) {
            return mapped;
        }
        mapped = methodsByNameAndDescriptor.get(name + descriptor);
        if (mapped != null && !mapped.equals(AMBIGUOUS)) {
            return mapped;
        }
        if (mapped != null) {
            String inherited = inheritedMethod(owner, name + descriptor);
            if (inherited != null) {
                return inherited;
            }
        }
        return unresolved(name, mapped != null);
    }

    /**
     * The Mojang name of a field a class file references or declares.
     *
     * @param descriptor the field descriptor in intermediary terms, or {@code null} when not known
     */
    public String mapField(String owner, String name, String descriptor) {
        if (!isIntermediaryMember(name)) {
            return name;
        }
        Map<String, String> own = fieldsByOwner.get(owner);
        String mapped = own == null ? null : own.get(name);
        if (mapped != null) {
            return mapped;
        }
        mapped = descriptor == null ? fieldsByName.get(name) : fieldsByNameAndDescriptor.get(name + ":" + descriptor);
        if (mapped != null && !mapped.equals(AMBIGUOUS)) {
            return mapped;
        }
        if (mapped != null) {
            String inherited = inheritedField(owner, name);
            if (inherited != null) {
                return inherited;
            }
        }
        return unresolved(name, mapped != null);
    }

    private String inheritedMethod(String owner, String nameAndDescriptor) {
        if (owner == null) {
            return null;
        }
        for (String ancestor : ancestors(owner)) {
            Map<String, String> own = methodsByOwner.get(ancestor);
            String mapped = own == null ? null : own.get(nameAndDescriptor);
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    private String inheritedMethodByName(String owner, String name) {
        if (owner == null) {
            return null;
        }
        List<String> chain = new ArrayList<>();
        chain.add(owner);
        chain.addAll(ancestors(owner));
        for (String ancestor : chain) {
            Map<String, String> own = methodsByOwner.get(ancestor);
            if (own == null) {
                continue;
            }
            Set<String> found = new HashSet<>();
            for (Map.Entry<String, String> entry : own.entrySet()) {
                if (entry.getKey().startsWith(name + "(")) {
                    found.add(entry.getValue());
                }
            }
            if (found.size() == 1) {
                return found.iterator().next();
            }
        }
        return null;
    }

    private String inheritedField(String owner, String name) {
        if (owner == null) {
            return null;
        }
        for (String ancestor : ancestors(owner)) {
            Map<String, String> own = fieldsByOwner.get(ancestor);
            String mapped = own == null ? null : own.get(name);
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    /** Superclasses and interfaces of {@code owner}, nearest first, without {@code owner}. */
    private List<String> ancestors(String owner) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(owner);
        Deque<String> pending = new ArrayDeque<>(parents.of(owner));
        while (!pending.isEmpty() && found.size() < MAX_DEPTH * 8) {
            String next = pending.removeFirst();
            if (seen.add(next)) {
                found.add(next);
                pending.addAll(parents.of(next));
            }
        }
        return found;
    }

    private String unresolved(String name, boolean ambiguous) {
        if (ambiguous) {
            unresolved.incrementAndGet();
            if (unresolvedExamples.size() < 20) {
                unresolvedExamples.add(name);
            }
        }
        return name;
    }

    /**
     * Translates every intermediary name inside a text that a mod keeps as a string: a Mixin annotation value ({@code
     * method_1523}, {@code Lnet/minecraft/class_310;method_1551()Lnet/minecraft/class_310;}, {@code net.minecraft.class_310}),
     * a Mixin reference map entry, or a constant a Mixin plug-in compares a target class name with. Text that holds no
     * intermediary name comes back as it is (the same instance).
     */
    public String mapText(String text) {
        if (text.length() < 6
                || !(text.contains("class_")
                        || text.contains("method_")
                        || text.contains("field_")
                        || text.contains("comp_"))) {
            return text;
        }
        String members = mapMembers(text);
        return mapClassNames(members);
    }

    /**
     * Translates the {@code method} value of a Mixin injector annotation ({@code @Inject(method = "method_5699")}). An
     * intermediary name names one method; the Mojang name may name several overloads, which Mixin would then all take
     * for the target (and a handler written for one of them fails to inject). So a bare numbered name whose method has
     * a single descriptor gets that descriptor, in Mojang names, and keeps meaning that one method. Anything else is
     * translated as {@link #mapText} does.
     */
    public String mapMethodSelector(String selector) {
        if (selector.startsWith("method_") && isIntermediaryMember(selector)) {
            String mojang = methodsByName.get(selector);
            String descriptor = methodDescriptorByName.get(selector);
            if (mojang != null && !mojang.equals(AMBIGUOUS) && descriptor != null && !descriptor.equals(AMBIGUOUS)) {
                return mojang + mapText(descriptor);
            }
        }
        return mapText(selector);
    }

    private String mapMembers(String text) {
        if (!(text.contains("method_") || text.contains("field_") || text.contains("comp_"))) {
            return text;
        }
        Matcher matcher = MEMBER_REF.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            String name = matcher.group("name");
            String owner = matcher.group("owner");
            String descriptor = matcher.group("desc");
            String internalOwner = owner == null ? null : owner.substring(1, owner.length() - 1);
            String mapped;
            if (name.startsWith("method_")) {
                if (descriptor != null && descriptor.charAt(0) == '(') {
                    mapped = mapMethod(internalOwner, name, descriptor);
                } else {
                    mapped = mapMethodByName(internalOwner, name);
                }
            } else {
                mapped = mapField(internalOwner, name, descriptor == null ? null : descriptor.substring(1));
            }
            if (!mapped.equals(name)) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 16);
                }
                out.append(text, last, matcher.start("name")).append(mapped);
                last = matcher.end("name");
            }
        }
        if (out == null) {
            return text;
        }
        return out.append(text, last, text.length()).toString();
    }

    /** A method reference without a descriptor: the name decides, the owner only breaks a tie. */
    private String mapMethodByName(String owner, String name) {
        String mapped = methodsByName.get(name);
        if (mapped != null && !mapped.equals(AMBIGUOUS)) {
            return mapped;
        }
        if (mapped != null && owner != null) {
            String inherited = inheritedMethodByName(owner, name);
            if (inherited != null) {
                return inherited;
            }
        }
        return unresolved(name, mapped != null);
    }

    private String mapClassNames(String text) {
        if (!text.contains("class_")) {
            return text;
        }
        Matcher matcher = CLASS_TOKEN.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            String token = matcher.group();
            if (!CLASS_NUMBER.matcher(token).find()) {
                continue;
            }
            boolean dotted = token.indexOf('.') >= 0;
            String internal = dotted ? token.replace('.', '/') : token;
            String prefix = "";
            String mapped = classes.get(internal);
            if (mapped == null) {
                // In a descriptor the L sits against the first name, and primitive types against the L:
                // Lnet/minecraft/class_1; and ZJLnet/minecraft/class_1;
                int letter = 0;
                while (letter < internal.length() && "BCDFIJSZ".indexOf(internal.charAt(letter)) >= 0) {
                    letter++;
                }
                if (letter < internal.length() - 1 && internal.charAt(letter) == 'L') {
                    mapped = classes.get(internal.substring(letter + 1));
                    prefix = internal.substring(0, letter + 1);
                }
            }
            if (mapped == null) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length() + 16);
            }
            out.append(text, last, matcher.start()).append(prefix).append(dotted ? mapped.replace('/', '.') : mapped);
            last = matcher.end();
        }
        if (out == null) {
            return text;
        }
        return out.append(text, last, text.length()).toString();
    }
}
