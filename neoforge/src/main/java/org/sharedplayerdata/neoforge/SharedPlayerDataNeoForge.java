package org.sharedplayerdata.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;

/**
 * NeoForge 桥接层。
 *
 * <h3>为什么不在构造函数里直接启动扫描</h3>
 * {@code @Mod} 的构造函数运行在 <b>mod loading 阶段</b>。此时 NeoForge 正用多个
 * worker 线程并行初始化各个 mod；如果在这里启动扫描线程去调
 * {@code Minecraft.getInstance()}，就会从这条线程触发 Minecraft 类的加载与静态
 * 初始化，与并行加载中的 worker 互相持有对方的类初始化锁，形成 JVM 类加载死锁。
 * 表现是游戏永远停在 <b>Loading mods</b>——大型整合包 mod 多、并行度高，几乎必然踩中。
 *
 * <p>所以这里改为注册 {@link FMLLoadCompleteEvent}：等所有 mod 都加载完毕、
 * 并行阶段结束之后，再让 {@link ClientBootstrap} 启动扫描线程。</p>
 *
 * <p>{@code FMLLoadCompleteEvent} 位于 {@code net.neoforged.fml.event.lifecycle}，
 * 在 NeoForge 1.20.2 ~ 1.21.x 稳定。万一将来包名变动导致注册抛异常，
 * 下面的 catch 会退回到「延迟 60 秒再启动」，那时 loading 早已结束，同样安全
 * ——只是扫描来得晚一些，不会死锁。</p>
 */
@Mod(SharedPlayerDataNeoForge.MOD_ID)
public final class SharedPlayerDataNeoForge {

    public static final String MOD_ID = "sharedplayerdata";

    public SharedPlayerDataNeoForge(IEventBus modEventBus) {
        try {
            modEventBus.addListener(SharedPlayerDataNeoForge::onLoadComplete);
        } catch (Throwable t) {
            // 事件注册不上（版本差异）→ 延迟启动兜底
            ClientBootstrap.startDelayed();
        }
    }

    private static void onLoadComplete(FMLLoadCompleteEvent event) {
        ClientBootstrap.start();
    }
}
