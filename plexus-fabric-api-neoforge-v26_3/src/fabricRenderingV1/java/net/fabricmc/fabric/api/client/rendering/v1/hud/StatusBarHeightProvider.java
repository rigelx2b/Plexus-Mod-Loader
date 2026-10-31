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
// fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/hud/StatusBarHeightProvider.java
// (Fabric API's file, unchanged in Forgified Fabric API).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; no other change.
package net.fabricmc.fabric.api.client.rendering.v1.hud;

import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Define the vertical space occupied by HUD elements, known as status bars, which are positioned on the left and right
 * sides above the player's hotbar.
 *
 * @see HudStatusBarHeightRegistry
 */
@FunctionalInterface
public interface StatusBarHeightProvider extends ToIntFunction<Player> {
    /**
     * @param player the {@link Player} from {@link Minecraft#getCameraEntity()}
     * @return the vertical space occupied by the status bar
     */
    int getStatusBarHeight(Player player);

    @ApiStatus.NonExtendable
    @Override
    default int applyAsInt(Player player) {
        return this.getStatusBarHeight(player);
    }
}
