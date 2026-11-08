package net.minecraftforge.common;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraftforge.eventbus.api.IEventBus;

/** Test stand-in for Forge's {@code MinecraftForge}: an {@code EVENT_BUS} that counts its starts. */
public final class MinecraftForge {

    public static final AtomicInteger STARTS = new AtomicInteger();

    public static final IEventBus EVENT_BUS = STARTS::incrementAndGet;

    private MinecraftForge() {}
}
