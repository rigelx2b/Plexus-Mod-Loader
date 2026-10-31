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
// fabric-data-generation-api-v1/src/main/java/net/fabricmc/fabric/api/datagen/v1/recipe/FabricRecipeOutput.java (Fabric
// API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.datagen.v1.recipe;

import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.Identifier;

/**
 * Injected to all {@link RecipeOutput} instances.
 */
public interface FabricRecipeOutput {
    /**
     * Override this method to change the recipe identifier.
     *
     * <p>The default implementation returns the ID unchanged.
     * Fabric API implementations automatically apply the corresponding method in
     * {@link net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider FabricRecipeProvider}.
     *
     * @see net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider#getRecipeIdentifier(Identifier)
     */
    default Identifier getRecipeIdentifier(Identifier recipeId) {
        return recipeId;
    }
}
