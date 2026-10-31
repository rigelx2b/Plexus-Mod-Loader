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
// fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/api/datagen/v1/provider/FabricTagAppender.java
// (Fabric API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; removing several entries or a tag goes through the remove methods
// NeoForge's patch gives TagAppender (ITagAppenderExtension) instead of failing with "Implemented via mixin".
package net.fabricmc.fabric.api.datagen.v1.provider;

import java.util.Collection;
import java.util.stream.Stream;
import net.fabricmc.fabric.impl.datagen.ForcedTagEntry;
import net.minecraft.data.tags.TagAppender;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagBuilder;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.common.extensions.ITagAppenderExtension;

/**
 * Interface-injected to {@link net.minecraft.data.tags.TagAppender}.
 */
@SuppressWarnings("unchecked")
public interface FabricTagAppender<T> extends ITagAppenderExtension<T> {
    /**
     * Sets the value of the {@code replace} flag. When set to {@code true}
     * this tag will replace contents of any other tag.
     *
     * @param replace whether to replace the contents of the tag
     * @return this, for chaining
     */
    default TagAppender<T> setReplace(boolean replace) {
        replace(replace);
        return (TagAppender<T>) this;
    }

    /**
     * Forces a tag key into the tag, bypassing any errors resulting from the
     * tag not existing at runtime.
     *
     * @param tag The tag to force into the contents of the tag
     * @return this, for chaining
     */
    default TagAppender<T> forceAddTag(TagKey<T> tag) {
        add(new ForcedTagEntry(tag.location()));
        return (TagAppender<T>) this;
    }

    /**
     * Removes an entry from the tag.
     *
     * @param element The entry to remove from the contents of the tag
     * @return this, for chaining
     */
    default TagAppender<T> remove(ResourceKey<T> element) {
        throw new AssertionError("Implemented via mixin");
    }

    /**
     * Removes multiple entries from the tag.
     *
     * @param elements The entries to remove from the contents of the tag
     * @return this, for chaining
     */
    default TagAppender<T> remove(final ResourceKey<T>... elements) {
        for (ResourceKey<T> element : elements) {
            remove(element);
        }

        return (TagAppender<T>) this;
    }

    /**
     * Removes multiple entries from the tag.
     *
     * @param elements The entries to remove from the contents of the tag
     * @return this, for chaining
     */
    default TagAppender<T> removeAll(final Collection<ResourceKey<T>> elements) {
        for (ResourceKey<T> element : elements) {
            remove(element);
        }

        return (TagAppender<T>) this;
    }

    /**
     * Removes multiple entries from the tag.
     *
     * @param elements The entries to remove from the contents of the tag
     * @return this, for chaining
     */
    default TagAppender<T> removeAll(final Stream<ResourceKey<T>> elements) {
        elements.forEach(element -> remove(element));
        return (TagAppender<T>) this;
    }

    /**
     * Removes all entries of the specified tag from the tag.
     *
     * @param tag The tag to remove from the contents of the tag
     * @return this, for chaining
     */
    default TagAppender<T> removeTag(TagKey<T> tag) {
        return remove(tag);
    }

    default TagBuilder getBuilder() {
        return null;
    }
}
