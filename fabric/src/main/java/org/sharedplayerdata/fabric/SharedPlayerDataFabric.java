package org.sharedplayerdata.fabric;

import java.util.UUID;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.sharedplayerdata.core.SpdLog;
import org.sharedplayerdata.core.SpdRuntime;
import org.sharedplayerdata.mcbridge.ClientScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 桥接层。
 *
 * <p>Fabric 在客户端初始化完成后才调用 {@code ClientModInitializer}，此时
 * {@code Minecraft.getInstance()} 通常已经可用，可以直接拿到当前登录玩家的 UUID。</p>
 *
 * <p>实际的等待 / 初始化 / 扫描 / 挂命令 / 启动看门狗都在
 * {@link ClientScanner} 里，与 Forge / NeoForge 共用同一份实现。</p>
 */
public final class SharedPlayerDataFabric implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("sharedplayerdata");

    @Override
    public void onInitializeClient() {
        SpdLog.setSink((level, message) -> {
            switch (level) {
                case ERROR:
                    LOGGER.error(message);
                    break;
                case WARN:
                    LOGGER.warn(message);
                    break;
                default:
                    LOGGER.info(message);
                    break;
            }
        });

        // 先用 loader 给的 gameDir 记一次，ClientScanner 拿到 Minecraft 实例后会
        // 用 mc.gameDirectory 再确认一次（两者通常相同）
        UUID uuid = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            User user = mc == null ? null : mc.getUser();
            if (user != null) {
                uuid = user.getProfileId();
            }
        } catch (Throwable t) {
            SpdLog.error("读取本地玩家信息失败", t);
        }
        SpdRuntime.init(FabricLoader.getInstance().getGameDir(), uuid);

        ClientScanner.start();
    }
}
