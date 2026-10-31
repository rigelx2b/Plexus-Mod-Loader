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
// fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/mixin/datagen/advancement/AdvancementBuilderMixin.java (Fabric API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.datagen.advancement;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.datagen.v1.advancement.FabricAdvancementBuilder;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Advancement.Builder.class)
abstract class AdvancementBuilderMixin implements FabricAdvancementBuilder {
    @Shadow
    public abstract AdvancementHolder build(Identifier id);

    @Override
    public AdvancementHolder save(Consumer<AdvancementHolder> output, Identifier id) {
        AdvancementHolder advancement = build(id);
        output.accept(advancement);
        return advancement;
    }
}
