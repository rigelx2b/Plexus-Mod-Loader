package dev.plexusmodloader.runtime.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.plexusmodloader.api.runtime.GameSide;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Forge on NeoForge (ADR-0051): whether the mixins of a MinecraftForge mod name only what NeoForge's patched game has.
 * NeoForge's FML applies a Forge mod's Mixin configurations (from its manifest's {@code MixinConfigs}, as {@code
 * [[mixins]]} entries) as it applies NeoForge mods'; a mod written for MinecraftForge's patched game may target a
 * class, method or call only Forge's patches have, which would stop the game or run the mod with that part missing.
 *
 * <p>For each configuration and each mixin of this side, the check reads the mixin class's annotations and finds, in the
 * game's classes: each target class, among the classes FML's Mixin transforms (not the loader libraries); each method an
 * injector ({@code @Inject}, {@code @Redirect}, {@code @ModifyArg}, {@code @ModifyArgs}) or {@code @Overwrite} names,
 * declared by the target; each call, field access or object creation its {@code @At} (and {@code @Slice}) names, in that method's code;
 * each member an {@code @Accessor} or {@code @Invoker} names, declared by the target; and each {@code @Shadow} member, in
 * the target or its supertypes. What it does not read leaves the mod out instead of being trusted: a configuration
 * plugin or a reference map the jar has, MixinExtras, {@code @ModifyVariable} and {@code @ModifyConstant} (whose local
 * and constant selectors it does not read), selector patterns, captured locals and other injection points.
 *
 * <p>Thread: stateless.
 */
public final class ForgeOnNeoForgeMixins {

    private static final String MIXIN_PACKAGE = "Lorg/spongepowered/asm/mixin/";
    private static final String MIXIN = MIXIN_PACKAGE + "Mixin;";
    private static final String PSEUDO = MIXIN_PACKAGE + "Pseudo;";
    private static final String SHADOW = MIXIN_PACKAGE + "Shadow;";
    private static final String OVERWRITE = MIXIN_PACKAGE + "Overwrite;";
    private static final String ACCESSOR = MIXIN_PACKAGE + "gen/Accessor;";
    private static final String INVOKER = MIXIN_PACKAGE + "gen/Invoker;";
    private static final String INJECTION = MIXIN_PACKAGE + "injection/";
    private static final String MIXIN_EXTRAS = "Lcom/llamalad7/mixinextras/";

    /** The injectors whose {@code method} and {@code at} the check reads. */
    private static final Set<String> INJECTORS =
            Set.of(INJECTION + "Inject;", INJECTION + "Redirect;", INJECTION + "ModifyArg;", INJECTION + "ModifyArgs;");

    /** Mixin annotations that change nothing the check reads. */
    private static final Set<String> NEUTRAL = Set.of(
            MIXIN_PACKAGE + "Unique;",
            MIXIN_PACKAGE + "Final;",
            MIXIN_PACKAGE + "Mutable;",
            MIXIN_PACKAGE + "Dynamic;",
            MIXIN_PACKAGE + "Debug;",
            MIXIN_PACKAGE + "Implements;",
            INJECTION + "Coerce;");

    /** Injection points without a member to find. */
    private static final Set<String> PLAIN_POINTS =
            Set.of("HEAD", "RETURN", "TAIL", "LOAD", "STORE", "JUMP", "CONSTANT");

    private static final Set<String> CALL_POINTS = Set.of("INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING");

    private ForgeOnNeoForgeMixins() {}

    /**
     * What keeps {@code jar}'s mixins from applying as written on {@code side}, one line per problem ({@code <config>
     * <mixin>: <what>}); empty when they fit or the jar has no Mixin configurations.
     *
     * @param gameClasses the class file of an internal name in the host's patched game or the other mods that run with
     *     it, when they have the class; the jar's own classes are looked up after them
     * @throws IOException when the jar cannot be read
     */
    public static List<String> unfit(Path jar, GameSide side, Function<String, Optional<byte[]>> gameClasses)
            throws IOException {
        return unfit(jar, side, gameClasses, name -> Optional.empty());
    }

    /**
     * As {@link #unfit(Path, GameSide, Function)}, with classes that may be supertypes of a target but not targets
     * themselves: those of the loader libraries, which FML's Mixin in the game layer never transforms.
     *
     * @param transformedClasses the class file of an internal name in the classes FML's Mixin transforms (the host's
     *     patched game, the other mods), when they have the class; the jar's own classes are looked up after them
     * @param libraryClasses the class file of an internal name in the loader libraries, for supertypes only
     * @throws IOException when the jar cannot be read
     */
    public static List<String> unfit(
            Path jar,
            GameSide side,
            Function<String, Optional<byte[]>> transformedClasses,
            Function<String, Optional<byte[]>> libraryClasses)
            throws IOException {
        List<String> problems = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            // A mixin may target the mod's own classes too.
            Function<String, Optional<byte[]>> targets = name -> {
                Optional<byte[]> game = transformedClasses.apply(name);
                if (game.isPresent()) {
                    return game;
                }
                try {
                    return read(file, name + ".class");
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read " + name + " from " + jar.getFileName(), e);
                }
            };
            Function<String, Optional<byte[]>> types = name -> {
                Optional<byte[]> target = targets.apply(name);
                return target.isPresent() ? target : libraryClasses.apply(name);
            };
            for (String config : configNames(file.getManifest())) {
                checkConfig(file, config, side, targets, types, problems);
            }
        }
        return List.copyOf(problems);
    }

    private static void checkConfig(
            JarFile file,
            String config,
            GameSide side,
            Function<String, Optional<byte[]>> targetClasses,
            Function<String, Optional<byte[]>> typeClasses,
            List<String> problems)
            throws IOException {
        Optional<byte[]> bytes = read(file, config);
        if (bytes.isEmpty()) {
            problems.add(config + " is not in the jar");
            return;
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(new String(bytes.get(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            problems.add(config + " is not a JSON object: " + e.getMessage());
            return;
        }
        if (json.has("plugin")) {
            problems.add(config + ": plugin " + text(json.get("plugin")) + " is not checked");
        }
        // A reference map the jar lacks is none: Mixin warns and uses the names as written.
        if (json.has("refmap") && file.getEntry(text(json.get("refmap"))) != null) {
            problems.add(config + ": refmap " + text(json.get("refmap")) + " is not checked");
        }
        String mixinPackage = json.has("package") ? text(json.get("package")) : "";
        List<String> mixins = new ArrayList<>(names(json.get("mixins")));
        mixins.addAll(names(json.get(side == GameSide.CLIENT ? "client" : "server")));
        for (String mixin : mixins) {
            String className = (mixinPackage.isEmpty() ? mixin : mixinPackage + "." + mixin).replace('.', '/');
            Optional<byte[]> classBytes = read(file, className + ".class");
            String where = config + " " + mixin;
            if (classBytes.isEmpty()) {
                problems.add(where + ": the mixin class is not in the jar");
                continue;
            }
            new MixinCheck(where, ClassFile.of().parse(classBytes.get()), targetClasses, typeClasses, problems).run();
        }
    }

    /** One mixin class against the game. */
    private record MixinCheck(
            String where,
            ClassModel mixin,
            Function<String, Optional<byte[]>> targetClasses,
            Function<String, Optional<byte[]>> typeClasses,
            List<String> problems) {

        void run() {
            List<Annotation> annotations = annotations(mixin.attributes());
            Optional<Annotation> mixinAnnotation = find(annotations, MIXIN);
            if (mixinAnnotation.isEmpty()) {
                problems.add(where + ": no @Mixin annotation");
                return;
            }
            boolean pseudo = find(annotations, PSEUDO).isPresent();
            List<ClassModel> targets = new ArrayList<>();
            for (String target : targets(mixinAnnotation.get())) {
                Optional<ClassModel> model = targetClasses.apply(target).map(ClassFile.of()::parse);
                if (model.isPresent()) {
                    targets.add(model.get());
                } else if (typeClasses.apply(target).isPresent()) {
                    problems.add(where + ": target " + dotted(target)
                            + " is in the loader libraries, which FML's Mixin does not transform");
                } else if (!pseudo) {
                    problems.add(where + ": target " + dotted(target) + " is not in the game");
                }
            }
            if (targets.isEmpty()) {
                return;
            }
            for (FieldModel field : mixin.fields()) {
                for (Annotation annotation : annotations(field.attributes())) {
                    String type = annotation.className().stringValue();
                    if (type.equals(SHADOW)) {
                        String name = field.fieldName().stringValue();
                        String descriptor = field.fieldType().stringValue();
                        for (ClassModel target : targets) {
                            if (!hasField(target, name, descriptor, true)) {
                                missing("@Shadow " + name + " (" + descriptor + ")", target);
                            }
                        }
                    } else {
                        other(type);
                    }
                }
            }
            for (MethodModel method : mixin.methods()) {
                checkParameters(method);
                for (Annotation annotation : annotations(method.attributes())) {
                    checkMethod(method, annotation, targets);
                }
            }
        }

        private void checkMethod(MethodModel method, Annotation annotation, List<ClassModel> targets) {
            String type = annotation.className().stringValue();
            String name = method.methodName().stringValue();
            String descriptor = method.methodType().stringValue();
            if (INJECTORS.contains(type)) {
                checkInjector(simpleName(type), annotation, targets);
            } else if (type.equals(SHADOW) || type.equals(OVERWRITE)) {
                for (ClassModel target : targets) {
                    if (!hasMethod(target, name, descriptor, type.equals(SHADOW))) {
                        missing(simpleName(type) + " " + name + descriptor, target);
                    }
                }
            } else if (type.equals(ACCESSOR)) {
                MethodTypeDesc signature = method.methodTypeSymbol();
                String fieldType = signature.parameterCount() == 0
                        ? signature.returnType().descriptorString()
                        : signature.parameterType(0).descriptorString();
                List<String> names = memberNames(annotation, name, List.of("get", "is", "set"));
                for (ClassModel target : targets) {
                    if (names.stream().noneMatch(field -> hasField(target, field, fieldType, false))) {
                        missing("@Accessor " + names.getFirst() + " (" + fieldType + ")", target);
                    }
                }
            } else if (type.equals(INVOKER)) {
                for (ClassModel target : targets) {
                    checkInvoker(annotation, name, method.methodTypeSymbol(), target);
                }
            } else {
                other(type);
            }
        }

        private void checkInvoker(Annotation annotation, String name, MethodTypeDesc signature, ClassModel target) {
            boolean constructor = (name.startsWith("new") || name.startsWith("create"))
                    && signature.returnType().equals(target.thisClass().asSymbol());
            if (constructor) {
                String descriptor = signature
                        .changeReturnType(java.lang.constant.ConstantDescs.CD_void)
                        .descriptorString();
                if (!hasMethod(target, "<init>", descriptor, false)) {
                    missing("@Invoker <init>" + descriptor, target);
                }
                return;
            }
            List<String> names = memberNames(annotation, name, List.of("call", "invoke"));
            String descriptor = signature.descriptorString();
            if (names.stream().noneMatch(method -> hasMethod(target, method, descriptor, false))) {
                missing("@Invoker " + names.getFirst() + descriptor, target);
            }
        }

        private void checkInjector(String injector, Annotation annotation, List<ClassModel> targets) {
            if (element(annotation, "target").isPresent()) {
                problems.add(where + ": " + injector + " target (@Desc) is not checked");
                return;
            }
            if (element(annotation, "locals")
                    .filter(value -> value instanceof AnnotationValue.OfEnum locals
                            && !locals.constantName().stringValue().equals("NO_CAPTURE"))
                    .isPresent()) {
                problems.add(where + ": " + injector + " captured locals are not checked");
                return;
            }
            List<Annotation> points = new ArrayList<>(nestedAnnotations(element(annotation, "at")));
            for (Annotation slice : nestedAnnotations(element(annotation, "slice"))) {
                points.addAll(nestedAnnotations(element(slice, "from")));
                points.addAll(nestedAnnotations(element(slice, "to")));
            }
            for (String selector : strings(element(annotation, "method"))) {
                Optional<Selector> parsed = Selector.parse(selector);
                if (parsed.isEmpty()) {
                    problems.add(where + ": " + injector + " method " + selector + " is not checked");
                    continue;
                }
                for (ClassModel target : targets) {
                    List<MethodModel> methods = parsed.get().in(target);
                    if (methods.isEmpty()) {
                        missing(injector + " method " + selector, target);
                        continue;
                    }
                    for (Annotation point : points) {
                        checkPoint(injector, point, target, methods);
                    }
                }
            }
        }

        private void checkPoint(String injector, Annotation at, ClassModel target, List<MethodModel> methods) {
            String value =
                    element(at, "value").map(ForgeOnNeoForgeMixins::string).orElse("");
            if (PLAIN_POINTS.contains(value)) {
                return;
            }
            if (element(at, "desc").isPresent()) {
                problems.add(where + ": " + injector + " at " + value + " with @Desc is not checked");
                return;
            }
            String member =
                    element(at, "target").map(ForgeOnNeoForgeMixins::string).orElse("");
            boolean known = CALL_POINTS.contains(value) || value.equals("FIELD") || value.equals("NEW");
            if (!known || member.isEmpty()) {
                problems.add(where + ": " + injector + " at " + value + " " + member + " is not checked");
                return;
            }
            for (MethodModel method : methods) {
                if (!contains(method, value, member)) {
                    problems.add(where + ": " + injector + " at " + value + " " + member + " is not in "
                            + dotted(target.thisClass().asInternalName()) + "#"
                            + method.methodName().stringValue());
                }
            }
        }

        private void checkParameters(MethodModel method) {
            method.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                    .ifPresent(parameters -> parameters
                            .parameterAnnotations()
                            .forEach(list -> list.forEach(
                                    annotation -> other(annotation.className().stringValue()))));
            method.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                    .ifPresent(parameters -> parameters
                            .parameterAnnotations()
                            .forEach(list -> list.forEach(
                                    annotation -> other(annotation.className().stringValue()))));
        }

        /** A Mixin or MixinExtras annotation the check does not read; others (the mod's own, JDK's) are fine. */
        private void other(String type) {
            if (type.startsWith(MIXIN_EXTRAS)) {
                problems.add(where + ": " + simpleName(type) + " (MixinExtras) is not checked");
            } else if (type.startsWith(MIXIN_PACKAGE) && !NEUTRAL.contains(type)) {
                problems.add(where + ": " + simpleName(type) + " is not checked");
            }
        }

        private void missing(String what, ClassModel target) {
            problems.add(where + ": " + what + " is not in "
                    + dotted(target.thisClass().asInternalName()));
        }

        private Optional<ClassModel> gameClass(String internalName) {
            return typeClasses.apply(internalName).map(ClassFile.of()::parse);
        }

        private boolean hasField(ClassModel type, String name, String descriptor, boolean inherited) {
            Set<String> seen = new HashSet<>();
            for (Optional<ClassModel> current = Optional.of(type); current.isPresent(); ) {
                ClassModel model = current.get();
                if (!seen.add(model.thisClass().asInternalName())) {
                    return false;
                }
                if (model.fields().stream()
                        .anyMatch(field -> field.fieldName().equalsString(name)
                                && field.fieldType().equalsString(descriptor))) {
                    return true;
                }
                if (!inherited) {
                    return false;
                }
                current = model.superclass().flatMap(entry -> gameClass(entry.asInternalName()));
            }
            return false;
        }

        private boolean hasMethod(ClassModel type, String name, String descriptor, boolean inherited) {
            if (declares(type, name, descriptor)) {
                return true;
            }
            if (!inherited) {
                return false;
            }
            Set<String> seen = new HashSet<>();
            List<ClassModel> pending = new ArrayList<>(List.of(type));
            while (!pending.isEmpty()) {
                ClassModel model = pending.removeLast();
                if (!seen.add(model.thisClass().asInternalName())) {
                    continue;
                }
                if (declares(model, name, descriptor)) {
                    return true;
                }
                model.superclass()
                        .flatMap(entry -> gameClass(entry.asInternalName()))
                        .ifPresent(pending::add);
                model.interfaces()
                        .forEach(entry -> gameClass(entry.asInternalName()).ifPresent(pending::add));
            }
            return false;
        }
    }

    /** A {@code method} selector: an optional owner, a name, and an optional descriptor. */
    private record Selector(String name, String descriptor) {

        /** The selector, or empty for what the check does not read (patterns, regular expressions, quantifiers). */
        static Optional<Selector> parse(String selector) {
            String text = selector;
            if (text.isEmpty() || text.contains("*") || text.startsWith("/") || text.contains("{")) {
                return Optional.empty();
            }
            if (text.startsWith("L") && text.indexOf(';') > 0 && text.indexOf(';') < text.indexOf('(')) {
                text = text.substring(text.indexOf(';') + 1);
            }
            int parenthesis = text.indexOf('(');
            return parenthesis < 0
                    ? Optional.of(new Selector(text, null))
                    : Optional.of(new Selector(text.substring(0, parenthesis), text.substring(parenthesis)));
        }

        List<MethodModel> in(ClassModel target) {
            return target.methods().stream()
                    .filter(method -> method.methodName().equalsString(name)
                            && (descriptor == null || method.methodType().equalsString(descriptor)))
                    .toList();
        }
    }

    /** Whether {@code method}'s code has the call, field access or object creation {@code target} names. */
    private static boolean contains(MethodModel method, String point, String target) {
        Optional<CodeAttribute> code = method.findAttribute(Attributes.code());
        if (code.isEmpty()) {
            return false;
        }
        for (CodeElement element : code.get().elementList()) {
            if (CALL_POINTS.contains(point) && element instanceof InvokeInstruction invoke) {
                if (matches(
                        target,
                        invoke.owner().asInternalName(),
                        invoke.name().stringValue(),
                        invoke.type().stringValue())) {
                    return true;
                }
            } else if (point.equals("FIELD") && element instanceof FieldInstruction field) {
                if (matches(
                        target,
                        field.owner().asInternalName(),
                        field.name().stringValue(),
                        ":" + field.type().stringValue())) {
                    return true;
                }
            } else if (point.equals("NEW") && element instanceof NewObjectInstruction creation) {
                String type = creation.className().asInternalName();
                String wanted = target.startsWith("L") && target.endsWith(";")
                        ? target.substring(1, target.length() - 1)
                        : target.replace('.', '/');
                if (type.equals(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether {@code target} ({@code Lowner;name(desc)ret} or {@code Lowner;name:desc}; owner and descriptor optional)
     * names {@code owner.name} with {@code descriptor} (a method's, or {@code :} and a field's).
     */
    private static boolean matches(String target, String owner, String name, String descriptor) {
        String rest = target;
        if (rest.startsWith("L") && rest.indexOf(';') > 0) {
            int end = rest.indexOf(';');
            if (!rest.substring(1, end).equals(owner)) {
                return false;
            }
            rest = rest.substring(end + 1);
        }
        int split = indexOfDescriptor(rest);
        String wantedName = split < 0 ? rest : rest.substring(0, split);
        return wantedName.equals(name) && (split < 0 || rest.substring(split).equals(descriptor));
    }

    private static int indexOfDescriptor(String text) {
        int parenthesis = text.indexOf('(');
        int colon = text.indexOf(':');
        if (parenthesis < 0) {
            return colon;
        }
        return colon < 0 ? parenthesis : Math.min(parenthesis, colon);
    }

    private static boolean declares(ClassModel type, String name, String descriptor) {
        return type.methods().stream()
                .anyMatch(method -> method.methodName().equalsString(name)
                        && method.methodType().equalsString(descriptor));
    }

    /**
     * The member names an accessor or invoker {@code method} may mean: its annotation's {@code value}, or the method
     * name without its first matching prefix, as Mixin infers it (lower-cased first letter, or as is when the rest is in
     * capitals, such as a constant's name).
     */
    private static List<String> memberNames(Annotation annotation, String method, List<String> prefixes) {
        Optional<String> value = element(annotation, "value").map(ForgeOnNeoForgeMixins::string);
        if (value.isPresent() && !value.get().isEmpty()) {
            return List.of(value.get());
        }
        for (String prefix : prefixes) {
            if (method.startsWith(prefix) && method.length() > prefix.length()) {
                String rest = method.substring(prefix.length());
                String lower = Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
                return rest.equals(rest.toUpperCase(Locale.ROOT)) ? List.of(rest, lower) : List.of(lower, rest);
            }
        }
        return List.of(method);
    }

    /** The internal names of {@code @Mixin}'s {@code value} classes and {@code targets} strings. */
    private static List<String> targets(Annotation mixin) {
        List<String> targets = new ArrayList<>();
        element(mixin, "value")
                .ifPresent(value -> values(value).forEach(element -> {
                    if (element instanceof AnnotationValue.OfClass type) {
                        ClassDesc desc = ClassDesc.ofDescriptor(type.className().stringValue());
                        targets.add(desc.descriptorString()
                                .substring(1, desc.descriptorString().length() - 1));
                    }
                }));
        strings(element(mixin, "targets")).forEach(target -> targets.add(target.replace('.', '/')));
        return targets;
    }

    private static List<String> configNames(Manifest manifest) {
        String value = manifest == null ? null : manifest.getMainAttributes().getValue("MixinConfigs");
        if (value == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String name : value.split(",")) {
            if (!name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    private static Optional<byte[]> read(JarFile file, String name) throws IOException {
        ZipEntry entry = file.getEntry(name);
        if (entry == null) {
            return Optional.empty();
        }
        try (InputStream input = file.getInputStream(entry)) {
            return Optional.of(input.readAllBytes());
        }
    }

    private static List<String> names(JsonElement list) {
        List<String> names = new ArrayList<>();
        if (list instanceof JsonArray array) {
            array.forEach(element -> names.add(text(element)));
        }
        return names;
    }

    private static String text(JsonElement element) {
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private static List<Annotation> annotations(List<? extends java.lang.classfile.Attribute<?>> attributes) {
        List<Annotation> annotations = new ArrayList<>();
        for (java.lang.classfile.Attribute<?> attribute : attributes) {
            if (attribute instanceof java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute invisible) {
                annotations.addAll(invisible.annotations());
            } else if (attribute instanceof java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute visible) {
                annotations.addAll(visible.annotations());
            }
        }
        return annotations;
    }

    private static Optional<Annotation> find(List<Annotation> annotations, String type) {
        return annotations.stream()
                .filter(annotation -> annotation.className().equalsString(type))
                .findFirst();
    }

    private static Optional<AnnotationValue> element(Annotation annotation, String name) {
        return annotation.elements().stream()
                .filter(element -> element.name().equalsString(name))
                .map(AnnotationElement::value)
                .findFirst();
    }

    private static List<AnnotationValue> values(AnnotationValue value) {
        return value instanceof AnnotationValue.OfArray array ? array.values() : List.of(value);
    }

    private static List<Annotation> nestedAnnotations(Optional<AnnotationValue> value) {
        List<Annotation> annotations = new ArrayList<>();
        value.ifPresent(present -> values(present).forEach(element -> {
            if (element instanceof AnnotationValue.OfAnnotation nested) {
                annotations.add(nested.annotation());
            }
        }));
        return annotations;
    }

    private static List<String> strings(Optional<AnnotationValue> value) {
        List<String> strings = new ArrayList<>();
        value.ifPresent(present -> values(present).forEach(element -> strings.add(string(element))));
        return strings;
    }

    private static String string(AnnotationValue value) {
        return value instanceof AnnotationValue.OfString text ? text.stringValue() : value.toString();
    }

    private static String simpleName(String descriptor) {
        String name = descriptor.substring(descriptor.lastIndexOf('/') + 1, descriptor.length() - 1);
        return "@" + name;
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }
}
