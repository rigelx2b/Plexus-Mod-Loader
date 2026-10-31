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
// fabric-menu-api-v1/src/main/java/net/fabricmc/fabric/mixin/menu/ServerPlayerMixin.java (Fabric API's
// file as Forgified Fabric API has it).
// Merged with Fabric API 0.165.0+26.3's version of the file (FabricMC/fabric, tag 0.165.0+26.3).
// Plexus Mod Loader (ADR-0031): reformatted; jspecify's Nullable for javax.annotation's, which is not on
// the class path (Fabric API uses jspecify).
package net.fabricmc.fabric.mixin.menu;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin extends Player {
    private ServerPlayerMixin(Level level, GameProfile gameProfile) {
        super(level, gameProfile);
    }

    @ModifyArg(
            method = "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ServerPlayer;openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;"),
            index = 0)
    private MenuProvider fabric_replaceMenuProvider(@Nullable MenuProvider arg) {
        if (arg instanceof SimpleMenuProvider simpleFactory
                && simpleFactory.menuConstructor instanceof ExtendedMenuProvider<?> extendedFactory) {
            return extendedFactory;
        }
        return arg;
    }

    @ModifyVariable(
            method = "openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;",
            at =
                    @At(
                            value = "INVOKE_ASSIGN",
                            target =
                                    "Lnet/minecraft/world/MenuProvider;createMenu(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/inventory/AbstractContainerMenu;"),
            argsOnly = true)
    private Consumer<RegistryFriendlyByteBuf> fabric_replaceExtraDataWriter(
            @Nullable Consumer<RegistryFriendlyByteBuf> extraDataWriter,
            MenuProvider arg,
            @Local @Nullable AbstractContainerMenu menu) {
        if (menu != null
                && arg instanceof ExtendedMenuProvider<?> extendedFactory
                && menu.getType() instanceof ExtendedMenuType extendedType) {
            return buf -> {
                Object data = extendedFactory.getScreenOpeningData((ServerPlayer) (Object) this);
                extendedType.getStreamCodec().encode(buf, data);
            };
        }
        return extraDataWriter;
    }
}
