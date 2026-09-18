package org.sharedplayerdata.forge;

import net.minecraftforge.fml.common.Mod;

/**
 * Forge 桥接层。
 *
 * <p>这里刻意不监听任何 Forge 事件：{@code FMLClientSetupEvent} 等类名在各版本间搬过包，
 * 而本模组只需要拿到客户端实例。改成在后台线程里等待客户端就绪，可以一套代码通吃
 * 1.19.2 ~ 1.21.x 的 Forge 与 NeoForge，不必为每个版本复制一份事件类。</p>
 */
@Mod(SharedPlayerDataForge.MOD_ID)
public final class SharedPlayerDataForge {

    public static final String MOD_ID = "sharedplayerdata";

    public SharedPlayerDataForge() {
        ClientBootstrap.start();
    }
}
