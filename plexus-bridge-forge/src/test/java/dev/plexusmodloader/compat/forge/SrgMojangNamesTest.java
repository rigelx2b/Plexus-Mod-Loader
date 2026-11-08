package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SrgMojangNamesTest {

    // The shape of the files Forge's installer tools write for 1.20.4 (checked against a real run, ADR-0038).
    private static final List<String> MERGED = List.of(
            "tsrg2 left right",
            "ss net/minecraft/nbt/IntTag",
            "\ta f_128670_",
            "\tb f_263440_",
            "\t<init> (I)V <init>",
            "\t\t0 o p_128674_",
            "\ta ()I m_263179_",
            "\ta (Lth;)Lth$b; m_196533_",
            "\t\t0 o p_197481_",
            "\te ()Lss; m_6426_",
            "th net/minecraft/nbt/TagVisitor",
            "\ta f_1_",
            "th$b net/minecraft/nbt/TagVisitor$ValueResult",
            "x net/minecraft/Unmapped",
            "\ta m_9_");

    private static final List<String> MOJANG = List.of(
            "net/minecraft/nbt/IntTag ss",
            "\tSELF_SIZE_IN_BYTES b",
            "\tTYPE a",
            "\t<init> (I)V <init>",
            "\tgetAsInt ()I a",
            "\taccept (Lnet/minecraft/nbt/TagVisitor;)Lnet/minecraft/nbt/TagVisitor$ValueResult; a",
            "\tcopy ()Lnet/minecraft/nbt/IntTag; e",
            "net/minecraft/nbt/TagVisitor th",
            "\tvisit a");

    @Test
    void joinsObfuscatedNamesThroughBothFiles(@TempDir Path directory) throws IOException {
        SrgMojangNames names = join(directory, MERGED, MOJANG);

        assertEquals("TYPE", names.translate("f_128670_"));
        assertEquals("SELF_SIZE_IN_BYTES", names.translate("f_263440_"));
        // The same obfuscated name "a" is a field, and two methods told apart by descriptor.
        assertEquals("getAsInt", names.translate("m_263179_"));
        assertEquals("accept", names.translate("m_196533_"));
        assertEquals("copy", names.translate("m_6426_"));
        assertEquals("visit", names.translate("f_1_"));
    }

    /** Forge 1.20.1 leaves Mojang's mappings as they come (ProGuard form), not sanitised. */
    @Test
    void joinsMojangMappingsInProguardForm(@TempDir Path directory) throws IOException {
        List<String> proguard = List.of(
                "# (c) 2020 Microsoft Corporation. These mappings are provided as-is.",
                "net.minecraft.nbt.IntTag -> ss:",
                "    int SELF_SIZE_IN_BYTES -> b",
                "    net.minecraft.nbt.TagType TYPE -> a",
                "    1:1:void <init>(int) -> <init>",
                "    80:80:int getAsInt() -> a",
                "    90:92:net.minecraft.nbt.TagVisitor$ValueResult accept(net.minecraft.nbt.TagVisitor) -> a",
                "    100:100:net.minecraft.nbt.IntTag copy() -> e",
                "net.minecraft.nbt.TagVisitor -> th:",
                "    void visit() -> a");

        SrgMojangNames names = join(directory, MERGED, proguard);

        assertEquals("TYPE", names.translate("f_128670_"));
        assertEquals("getAsInt", names.translate("m_263179_"));
        assertEquals("accept", names.translate("m_196533_"));
        assertEquals("copy", names.translate("m_6426_"));
        assertEquals(5, names.size());
    }

    @Test
    void keepsNamesWithoutMojangName(@TempDir Path directory) throws IOException {
        SrgMojangNames names = join(directory, MERGED, MOJANG);

        assertEquals("m_9_", names.translate("m_9_"));
        assertEquals("tick", names.translate("tick"));
        assertEquals(6, names.size());
    }

    @Test
    void translatesOnlyWholeSrgNamesInText(@TempDir Path directory) throws IOException {
        SrgMojangNames names = SrgMojangNames.of(Map.of("m_1_", "tick", "f_2_", "level"));

        assertEquals(
                "public net.minecraft.Foo tick()V # level",
                names.translateText("public net.minecraft.Foo m_1_()V # f_2_"));
        assertEquals("Lfoo;tick(I)V", names.translateText("Lfoo;m_1_(I)V"));
        // Not an SRG name by itself: part of a longer identifier.
        assertEquals("xm_1_ m_1_x m_3_", names.translateText("xm_1_ m_1_x m_3_"));
        assertTrue(SrgMojangNames.isSrgName("m_123_"));
        assertFalse(SrgMojangNames.isSrgName("m_123_x"));
        assertFalse(SrgMojangNames.isSrgName("tick"));
    }

    @Test
    void writesAndReadsTheTable(@TempDir Path directory) throws IOException {
        SrgMojangNames names = join(directory, MERGED, MOJANG);
        Path file = directory.resolve("table.txt");

        names.write(file);

        SrgMojangNames read = SrgMojangNames.read(file);
        assertEquals(names.size(), read.size());
        assertEquals("getAsInt", read.translate("m_263179_"));
    }

    /** Two methods of one Mojang name in a class: their SRG names keep the Mojang descriptor apart, also when read back. */
    @Test
    void keepsTheDescriptorsOfOverloadedMethods(@TempDir Path directory) throws IOException {
        List<String> merged = List.of(
                "tsrg2 left right",
                "fee net/minecraft/client/model/geom/ModelPart",
                "\ta (Leij;Leio;II)V m_104301_",
                "\ta (Leij;Leio;IIFFFF)V m_104306_",
                "\tb ()V m_1_",
                "\tc (Leij;)V m_6973_",
                "\tc (Leio;)V m_6974_",
                "fef net/minecraft/client/model/ParrotModel",
                "\tc (Leio;)V m_6973_",
                "eij com/mojang/blaze3d/vertex/PoseStack",
                "eio com/mojang/blaze3d/vertex/VertexConsumer");
        List<String> mojang = List.of(
                "net/minecraft/client/model/geom/ModelPart fee",
                "\trender (Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V a",
                "\trender (Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V a",
                "\ttick ()V b",
                "\tsetupAnim (Lcom/mojang/blaze3d/vertex/PoseStack;)V c",
                "\tsetupAnim (Lcom/mojang/blaze3d/vertex/VertexConsumer;)V c",
                "net/minecraft/client/model/ParrotModel fef",
                "\tsetupAnim (Lcom/mojang/blaze3d/vertex/VertexConsumer;)V c");

        SrgMojangNames names = join(directory, merged, mojang);
        Path file = directory.resolve("table.txt");
        names.write(file);
        SrgMojangNames read = SrgMojangNames.read(file);

        for (SrgMojangNames table : List.of(names, read)) {
            assertEquals("render", table.translate("m_104306_"));
            assertEquals(
                    "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
                    table.methodSelector("m_104306_"));
            assertEquals(
                    "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V",
                    table.methodSelector("m_104301_"));
            // Not overloaded: the name alone, as before.
            assertEquals("tick", table.methodSelector("m_1_"));
            // Overridden with another descriptor in another class: the id does not say which, the name alone.
            assertEquals("setupAnim", table.methodSelector("m_6973_"));
            assertEquals("m_9_", table.methodSelector("m_9_"));
        }
    }

    @Test
    void refusesAFileThatIsNotTsrg2(@TempDir Path directory) throws IOException {
        Path merged = Files.write(directory.resolve("merged"), List.of("tsrg obf srg"));
        Path mojang = Files.write(directory.resolve("mojang"), MOJANG);

        assertThrows(IOException.class, () -> SrgMojangNames.join(merged, mojang));
    }

    private static SrgMojangNames join(Path directory, List<String> merged, List<String> mojang) throws IOException {
        return SrgMojangNames.join(
                Files.write(directory.resolve("merged"), merged), Files.write(directory.resolve("mojang"), mojang));
    }
}
