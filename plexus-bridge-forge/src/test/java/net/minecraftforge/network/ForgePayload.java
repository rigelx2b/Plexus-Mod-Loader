package net.minecraftforge.network;

import net.minecraft.resources.Identifier;

/** Test stand-in for Forge's {@code ForgePayload}: only {@code id()}, which {@code ForgeForeignPayloads} reads. */
public record ForgePayload(Identifier id) {}
