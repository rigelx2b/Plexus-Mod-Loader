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
// fabric-rendering-fluids-v1/src/client/java/net/fabricmc/fabric/impl/client/rendering/fluid/FluidRenderingImpl.java
// (Fabric API's
// file as Forgified Fabric API has it).
// Fabric API 0.165.0+26.3 has the same file as 0.161.0+26.2.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.client.rendering.fluid;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRendering;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public class FluidRenderingImpl {
    private static final ScopedValue<FluidRendering.DefaultRenderer> CURRENT_DEFAULT_RENDERER =
            ScopedValue.newInstance();
    public static final ScopedValue<Void> IS_RENDERING_VANILLA_DEFAULT = ScopedValue.newInstance();

    // Only invoked manually from FluidRendering#render
    public static void render(
            FluidRenderer fluidRenderer,
            FluidRenderHandler handler,
            BlockAndTintGetter level,
            BlockPos pos,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState,
            FluidRendering.DefaultRenderer defaultRenderer) {
        ScopedValue.where(CURRENT_DEFAULT_RENDERER, defaultRenderer).run(() -> {
            handler.renderFluid(fluidRenderer, pos, level, output, blockState, fluidState);
        });
    }

    // Only invoked when FluidRenderHandler#renderFluid calls super
    public static void renderDefault(
            FluidRenderer fluidRenderer,
            FluidRenderHandler handler,
            BlockAndTintGetter level,
            BlockPos pos,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState) {
        if (CURRENT_DEFAULT_RENDERER.isBound()) {
            CURRENT_DEFAULT_RENDERER.get().render(fluidRenderer, handler, level, pos, output, blockState, fluidState);
        } else {
            renderVanillaDefault(fluidRenderer, level, pos, output, blockState, fluidState);
        }
    }

    // Invoked when FluidRenderHandler#renderFluid is called directly without using FluidRendering#render (such as
    // from vanilla LiquidBlockRenderer#render via mixin) or from the default implementation of DefaultRenderer#render
    public static void renderVanillaDefault(
            FluidRenderer fluidRenderer,
            BlockAndTintGetter level,
            BlockPos pos,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState) {
        ScopedValue.where(IS_RENDERING_VANILLA_DEFAULT, null)
                .run(() -> fluidRenderer.tesselate(level, pos, output, blockState, fluidState));
    }
}
