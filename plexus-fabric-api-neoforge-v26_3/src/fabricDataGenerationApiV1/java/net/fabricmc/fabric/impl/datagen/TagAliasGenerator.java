/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Source: Sinytra Forgified Fabric API, branch 26.2.x, commit 40f8ce0,
// fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/impl/datagen/TagAliasGenerator.java (Fabric API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.datagen;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.impl.tag.TagAliasGroup;
import net.minecraft.core.Registry;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

public final class TagAliasGenerator {
    public static String getDirectory(ResourceKey<? extends Registry<?>> registryKey) {
        String directory = "fabric/tag_aliases/";
        Identifier registryId = registryKey.identifier();

        if (!Identifier.DEFAULT_NAMESPACE.equals(registryId.getNamespace())) {
            directory += registryId.getNamespace() + '/';
        }

        return directory + registryId.getPath();
    }

    public static <T> CompletableFuture<?> writeTagAlias(
            CachedOutput cache,
            PackOutput.PathProvider pathResolver,
            ResourceKey<? extends Registry<T>> registryRef,
            Identifier groupId,
            List<TagKey<T>> tags) {
        Path path = pathResolver.json(groupId);
        return DataProvider.saveStable(cache, TagAliasGroup.codec(registryRef), new TagAliasGroup<>(tags), path);
    }
}
