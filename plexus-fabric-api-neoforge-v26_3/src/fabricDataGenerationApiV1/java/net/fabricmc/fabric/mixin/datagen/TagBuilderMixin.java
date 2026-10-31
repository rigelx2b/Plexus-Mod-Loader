package net.fabricmc.fabric.mixin.datagen;

import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagBuilder;
import net.minecraft.tags.TagBuilder;
import org.spongepowered.asm.mixin.Mixin;

// Plexus Mod Loader (ADR-0031). Fabric API 0.165.0+26.3's TagBuilderMixin keeps its own removal list and remove
// methods; NeoForge 26.3's patch gives TagBuilder the same methods over its own list, which NeoForge's TagsProvider
// writes. Next to NeoForge the mixin only makes TagBuilder a FabricTagBuilder, whose methods go to NeoForge's.
@Mixin(TagBuilder.class)
abstract class TagBuilderMixin implements FabricTagBuilder {}
