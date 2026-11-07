package dev.plexusmodloader.compat.fabric.intermediary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntermediaryTranslationTest {

    private static final String LEVEL = "net/minecraft/class_1";
    private static final String OTHER = "net/minecraft/class_2";

    @Test
    void classesBecomeMojangNamesAndUnlistedNamesStay() {
        IntermediaryTranslation translation = TestMappings.translation();
        assertEquals("net/minecraft/world/Level", translation.mapClass(LEVEL));
        assertEquals("net/minecraft/server/Other", translation.mapClass(OTHER));
        assertEquals("net/minecraft/server/Other$Inner", translation.mapClass("net/minecraft/class_2$class_3"));
        assertEquals("net/minecraft/world/Dimension", translation.mapClass("net/minecraft/class_4"));
        assertEquals("com/mojang/Kept", translation.mapClass("com/mojang/Kept"));
        assertEquals("java/lang/String", translation.mapClass("java/lang/String"));
    }

    @Test
    void membersAreMappedByOwnerAndDescriptorInIntermediaryTerms() {
        IntermediaryTranslation translation = TestMappings.translation();
        assertEquals("count", translation.mapField(LEVEL, "field_10", "I"));
        assertEquals("other", translation.mapField(LEVEL, "field_11", "Lnet/minecraft/class_2;"));
        assertEquals("size", translation.mapMethod(LEVEL, "method_21", "()I"));
        assertEquals("setCount", translation.mapMethod(LEVEL, "method_22", "(I)V"));
        // A name that is not Fabric's numbered name is the game's own and stays.
        assertEquals("toString", translation.mapMethod(LEVEL, "toString", "()Ljava/lang/String;"));
        assertEquals("<init>", translation.mapMethod(LEVEL, "<init>", "()V"));
    }

    @Test
    void uniqueMembersAreFoundThroughAnyOwnerAKnownSubclassIncluded() {
        IntermediaryTranslation translation = TestMappings.translation();
        // A mod class that overrides method_21 is not a game class, so its own name is not in the tables.
        assertEquals("size", translation.mapMethod("com/example/MyLevel", "method_21", "()I"));
        assertEquals("count", translation.mapField("com/example/MyLevel", "field_10", "I"));
    }

    @Test
    void sameNumberInUnrelatedClassesIsDecidedByTheOwnerOrTheHierarchy() {
        IntermediaryTranslation translation = TestMappings.translation();
        String descriptor = "(Lnet/minecraft/class_1;)V";
        assertEquals(1, translation.ambiguousMethodCount());
        assertEquals("tick", translation.mapMethod(LEVEL, "method_20", descriptor));
        assertEquals("run", translation.mapMethod(OTHER, "method_20", descriptor));

        // Without a hierarchy a subclass of class_2 cannot be decided: the name stays and is counted.
        assertEquals("method_20", translation.mapMethod("com/example/MyOther", "method_20", descriptor));
        assertEquals(1, translation.unresolved());
        assertEquals(List.of("method_20"), translation.unresolvedExamples());

        IntermediaryTranslation withHierarchy =
                translation.withParents(type -> type.equals("com/example/MyOther") ? List.of(OTHER) : List.of());
        assertEquals("run", withHierarchy.mapMethod("com/example/MyOther", "method_20", descriptor));
        assertEquals(0, withHierarchy.unresolved());
        // The base keeps its own counter.
        assertEquals(1, translation.unresolved());
    }

    @Test
    void textWithoutIntermediaryNamesIsReturnedAsIs() {
        IntermediaryTranslation translation = TestMappings.translation();
        String text = "net.minecraft.world.Level.tick and nothing numbered";
        assertSame(text, translation.mapText(text));
    }

    @Test
    void mixinTargetsAndReferencesAreTranslated() {
        IntermediaryTranslation translation = TestMappings.translation();
        // @Inject(method = "...")
        assertEquals("size", translation.mapText("method_21"));
        assertEquals("size()I", translation.mapText("method_21()I"));
        // @At(target = "...")
        assertEquals("Lnet/minecraft/world/Level;size()I", translation.mapText("Lnet/minecraft/class_1;method_21()I"));
        assertEquals(
                "Lnet/minecraft/server/Other;run(Lnet/minecraft/world/Level;)V",
                translation.mapText("Lnet/minecraft/class_2;method_20(Lnet/minecraft/class_1;)V"));
        // @Redirect field reads
        assertEquals("Lnet/minecraft/world/Level;count:I", translation.mapText("Lnet/minecraft/class_1;field_10:I"));
        // @Mixin(targets = ...) in both spellings
        assertEquals("net.minecraft.world.Level", translation.mapText("net.minecraft.class_1"));
        assertEquals("net/minecraft/server/Other$Inner", translation.mapText("net/minecraft/class_2$class_3"));
        // Primitive parameters sit against the L of the next class: (ZJLnet/minecraft/class_1;)V
        assertEquals(
                "Lnet/minecraft/server/Other;<init>(ZJLnet/minecraft/world/Level;[Lnet/minecraft/world/Level;I)V",
                translation.mapText(
                        "Lnet/minecraft/class_2;<init>(ZJLnet/minecraft/class_1;[Lnet/minecraft/class_1;I)V"));
        // A name the mappings do not know stays; so does the text around it.
        assertEquals("see net.minecraft.class_999 here", translation.mapText("see net.minecraft.class_999 here"));
        assertEquals("a net.minecraft.world.Level b", translation.mapText("a net.minecraft.class_1 b"));
    }

    @Test
    void aBareMethodSelectorKeepsNamingOneMethodWhenTheMojangNameIsOverloaded() {
        IntermediaryTranslation translation = TestMappings.translation();
        // method_21 has one descriptor, so the selector gets it: Mixin would otherwise take every overload of "size".
        assertEquals("size()I", translation.mapMethodSelector("method_21"));
        assertEquals("setCount(I)V", translation.mapMethodSelector("method_22"));
        // method_20 means different Mojang methods in two classes: a selector cannot say which, so it stays.
        assertEquals("method_20", translation.mapMethodSelector("method_20"));
        // A selector that already has a descriptor, a constructor, or a plain game name is translated as text.
        assertEquals("size()I", translation.mapMethodSelector("method_21()I"));
        assertEquals("<init>", translation.mapMethodSelector("<init>"));
        assertEquals("tick", translation.mapMethodSelector("tick"));
    }

    @Test
    void anAmbiguousReferenceWithItsOwnerIsDecidedByTheOwner() {
        IntermediaryTranslation translation = TestMappings.translation();
        assertEquals(
                "Lnet/minecraft/world/Level;tick(Lnet/minecraft/world/Level;)V",
                translation.mapText("Lnet/minecraft/class_1;method_20(Lnet/minecraft/class_1;)V"));
    }

    @Test
    void anotherNamespaceIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IntermediaryMappings.parse(new ByteArrayInputStream(
                        "tiny\t2\t0\tofficial\tnamed\nc\ta\tb\n".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void numberedNamesAreRecognised() {
        assertTrue(IntermediaryTranslation.isIntermediaryMember("method_8320"));
        assertTrue(IntermediaryTranslation.isIntermediaryMember("field_1"));
        assertTrue(IntermediaryTranslation.isIntermediaryMember("comp_2045"));
        assertFalse(IntermediaryTranslation.isIntermediaryMember("method_"));
        assertFalse(IntermediaryTranslation.isIntermediaryMember("method_12a"));
        assertFalse(IntermediaryTranslation.isIntermediaryMember("methods_1"));
        assertFalse(IntermediaryTranslation.isIntermediaryMember("lambda$tick$0"));
        assertFalse(IntermediaryTranslation.isIntermediaryMember("tick"));
    }

    @Test
    void theHierarchyOfTheGameIsReadInIntermediaryTerms(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp)
            throws Exception {
        // A jar with a class "net/minecraft/server/Other" that extends "net/minecraft/world/Level".
        java.nio.file.Path jar = temp.resolve("game.jar");
        try (java.util.zip.ZipOutputStream out =
                new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
            writer.visit(
                    org.objectweb.asm.Opcodes.V21,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    "net/minecraft/server/Other",
                    null,
                    "net/minecraft/world/Level",
                    new String[] {"java/lang/Runnable"});
            writer.visitEnd();
            out.putNextEntry(new java.util.zip.ZipEntry("net/minecraft/server/Other.class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        IntermediaryTranslation translation = TestMappings.translation();
        GameParents parents = new GameParents(jar, translation);
        assertEquals(List.of(LEVEL, "java/lang/Runnable"), parents.of(OTHER));
        assertEquals(List.of(), parents.of("net/minecraft/class_4"));
    }
}
