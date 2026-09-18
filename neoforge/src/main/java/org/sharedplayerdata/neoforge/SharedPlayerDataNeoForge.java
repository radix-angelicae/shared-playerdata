package org.sharedplayerdata.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge 桥接层（与 Forge 版逻辑一致，等待客户端就绪后扫描存档）。
 */
@Mod(SharedPlayerDataNeoForge.MOD_ID)
public final class SharedPlayerDataNeoForge {

    public static final String MOD_ID = "sharedplayerdata";

    public SharedPlayerDataNeoForge(IEventBus modEventBus) {
        ClientBootstrap.start();
    }
}
