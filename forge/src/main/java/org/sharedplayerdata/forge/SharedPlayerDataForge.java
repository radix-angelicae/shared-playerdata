package org.sharedplayerdata.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge 桥接层。
 *
 * <h3>为什么不在构造函数里直接启动扫描</h3>
 * {@code @Mod} 的构造函数运行在 <b>mod loading 阶段</b>。此时 Forge 正并行初始化
 * 各个 mod；如果在这里启动扫描线程去调 {@code Minecraft.getInstance()}，就会从这条
 * 线程触发 Minecraft 类的加载与静态初始化，与并行加载中的 worker 互相持有对方的
 * 类初始化锁，形成 JVM 类加载死锁，表现是游戏永远停在 <b>Loading mods</b>。
 *
 * <p>所以改为注册 {@link FMLLoadCompleteEvent}：等所有 mod 加载完毕、并行阶段
 * 结束之后再启动扫描线程。mod 少时这套死锁未必发作（这也是 1.20.1 单 mod 环境
 * 一直正常的原因），但整合包里 mod 一多就会暴露。</p>
 *
 * <p>{@code FMLLoadCompleteEvent} 位于 {@code net.minecraftforge.fml.event.lifecycle}，
 * 在 Forge 1.19.2 ~ 1.20.6 稳定。万一注册抛异常，catch 会退回到「延迟 60 秒再启动」，
 * 那时 loading 早已结束，同样不会死锁。</p>
 */
@Mod(SharedPlayerDataForge.MOD_ID)
public final class SharedPlayerDataForge {

    public static final String MOD_ID = "sharedplayerdata";

    public SharedPlayerDataForge() {
        try {
            FMLJavaModLoadingContext.get().getModEventBus()
                    .addListener(SharedPlayerDataForge::onLoadComplete);
        } catch (Throwable t) {
            // 事件注册不上（版本差异）→ 延迟启动兜底
            ClientBootstrap.startDelayed();
        }
    }

    private static void onLoadComplete(FMLLoadCompleteEvent event) {
        ClientBootstrap.start();
    }
}
