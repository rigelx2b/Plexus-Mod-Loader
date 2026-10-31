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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/mixin/client/rendering/RenderPipelineBuilderMixin.java
// (Fabric API's file as Forgified Fabric API changed it for NeoForge).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; buildSnippet is wrapped as a whole (@WrapMethod) instead of its new
// RenderPipeline.Snippet (@WrapOperation at NEW, written in Forgified Fabric API for the constructor NeoForge's patch
// calls, with its StencilTest, in 26.2): Plexus Mod Loader's host check takes a NEW without a constructor for one
// written for the
// vanilla constructor, and with the constructor named in the NEW target the wrap found nothing to inject into (critical
// injection failure, 0/1). The snippet is still the only thing buildSnippet constructs inside the scope that carries
// usePipelineDrawModeForGui.
package net.fabricmc.fabric.mixin.client.rendering;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderPipeline;
import net.fabricmc.fabric.impl.client.rendering.FabricRenderPipelineImpl;
import net.fabricmc.fabric.impl.client.rendering.FabricRenderPipelineInternals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderPipeline.Builder.class)
class RenderPipelineBuilderMixin implements FabricRenderPipeline.Builder {
    @Unique
    private Optional<Boolean> usePipelineDrawModeForGui = Optional.empty();

    @Override
    public RenderPipeline.Builder withUsePipelineDrawModeForGui(boolean usePipelineDrawMode) {
        this.usePipelineDrawModeForGui = Optional.of(usePipelineDrawMode);
        return (RenderPipeline.Builder) (Object) this;
    }

    @Override
    public RenderPipeline.Builder withoutUsePipelineDrawModeForGui() {
        this.usePipelineDrawModeForGui = Optional.empty();
        return (RenderPipeline.Builder) (Object) this;
    }

    @Inject(method = "withSnippet", at = @At("TAIL"))
    private void copyUsePipelineDrawModeForGuiFromSnippet(
            RenderPipeline.Snippet snippet, CallbackInfoReturnable<RenderPipeline.Builder> cir) {
        ((FabricRenderPipeline.Snippet) (Object) snippet)
                .usePipelineDrawModeForGui()
                .ifPresent(value -> this.usePipelineDrawModeForGui = Optional.of(value));
    }

    @WrapMethod(method = "buildSnippet")
    private RenderPipeline.Snippet copyUsePipelineDrawModeForGuiToSnippet(Operation<RenderPipeline.Snippet> original) {
        return FabricRenderPipelineInternals.withSnippetUsePipelineVertexFormatForGui(
                original::call, usePipelineDrawModeForGui);
    }

    @ModifyReturnValue(method = "build", at = @At("RETURN"))
    private RenderPipeline copyUsePipelineDrawModeForGuiToPipeline(RenderPipeline original) {
        ((FabricRenderPipelineImpl) original)
                .fabric$setUsePipelineDrawModeForGuiSetter(this.usePipelineDrawModeForGui.orElse(false));
        return original;
    }
}
