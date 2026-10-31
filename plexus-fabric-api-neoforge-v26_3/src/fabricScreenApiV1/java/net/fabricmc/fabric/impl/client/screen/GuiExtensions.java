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
// fabric-screen-api-v1/src/client/java/net/fabricmc/fabric/impl/client/screen/GuiExtensions.java (Fabric API's
// file as Forgified Fabric API has it).
// Fabric API 0.165.0+26.3 has the same file as 0.161.0+26.2.
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.impl.client.screen;

import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

public interface GuiExtensions {
    @Nullable
    Screen getTickingScreen();

    void setTickingScreen(@Nullable Screen screen);
}
