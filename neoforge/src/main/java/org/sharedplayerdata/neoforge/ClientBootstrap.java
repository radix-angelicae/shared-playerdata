package org.sharedplayerdata.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.sharedplayerdata.mcbridge.ClientScanner;

/**
 * 客户端引导。专用服务器上直接返回，绝不触碰 Minecraft 客户端类。
 *
 * <p>刻意把 Minecraft 相关的代码全部放进 {@link org.sharedplayerdata.mcbridge.ClientScanner}：本类自身不引用任何
 * 客户端类，因此 {@code FMLEnvironment.dist} 的判断可以在加载 ClientScanner 之前生效，
 * 避免在专用服务器（客户端类已被剥离）上触发 NoClassDefFoundError。</p>
 */
public final class ClientBootstrap {

    private ClientBootstrap() {
    }

    public static void start() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return; // 专用服务器：什么都不做
        }
        ClientScanner.start();
    }

    /**
     * 兜底：延迟后再启动。只在 {@code FMLLoadCompleteEvent} 注册失败时使用，
     * 目的是让 mod loading 阶段彻底过去，再让扫描线程去碰 Minecraft 类。
     */
    public static void startDelayed() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        ClientScanner.startDelayed();
    }
}
