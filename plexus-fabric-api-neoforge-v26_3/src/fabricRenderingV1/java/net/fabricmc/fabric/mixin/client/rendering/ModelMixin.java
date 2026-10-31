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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/mixin/client/rendering/ModelMixin.java (Fabric API's file,
// unchanged in Forgified Fabric API).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.mixin.client.rendering;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.FabricModel;
import net.fabricmc.fabric.impl.client.rendering.ModelExtensions;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Model.class)
abstract class ModelMixin<S> implements FabricModel<S>, ModelExtensions {
    @Shadow
    public abstract ModelPart root();

    @Unique
    private final Map<String, ModelPart> childPartMap = new Object2ObjectOpenHashMap<>();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fillChildPartMap(ModelPart root, Function<Identifier, RenderType> layerFactory, CallbackInfo ci) {
        this.fabric$calculateChildParts(root);
    }

    @Override
    public void fabric$calculateChildParts(ModelPart root) {
        ((ModelPartAccessor) (Object) root).fabric$callAddAllChildren(childPartMap::putIfAbsent);
    }

    @Override
    @Nullable
    public ModelPart getChildPart(String name) {
        return childPartMap.get(name);
    }

    @Override
    public void copyTransforms(Model<?> model) {
        copyTransforms(model.root(), root());
        ((ModelPartAccessor) (Object) model.root()).fabric$callAddAllChildren((name, part) -> {
            ModelPart childPart = getChildPart(name);

            if (childPart != null) {
                copyTransforms(part, childPart);
            }
        });
    }

    @Unique
    private static void copyTransforms(ModelPart from, ModelPart to) {
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
        to.xRot = from.xRot;
        to.yRot = from.yRot;
        to.zRot = from.zRot;
        to.xScale = from.xScale;
        to.yScale = from.yScale;
        to.zScale = from.zScale;
    }
}
