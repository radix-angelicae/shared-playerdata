package org.sharedplayerdata.mcbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.sharedplayerdata.core.SpdPlatform;

/**
 * 用<b>编译期</b>的 Minecraft 符号实现 {@link SpdPlatform}。
 *
 * <p>这里的每个 {@code mc.level}、{@code Component.literal} 都会在构建时被正确
 * 重映射（Forge 到 SRG，Fabric 到 intermediary），所以在玩家机器上真的有效。
 * 这正是核心模块做不到的：核心里写 "level" 这种字符串，混淆后必然找不到。</p>
 *
 * <p>每个方法都独立 try/catch：某个 API 在个别版本上不存在时，只是那一项功能
 * 不可用，不会把游戏搞崩。</p>
 *
 * <p>三个加载器共用：MC 的类名在官方映射下是一致的，
 * 所以这份代码可以直接以源码形式并入三个工程。</p>
 */
public final class PlatformImpl implements SpdPlatform {

    private PlatformImpl() {
    }

    public static void install() {
        SpdPlatform.set(new PlatformImpl());
    }

    @Override
    public Object minecraft() {
        try {
            return Minecraft.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean isInWorld() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.level != null;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void sendMessage(String text) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || text == null) {
                return;
            }
            LocalPlayer player = mc.player;
            if (player == null) {
                return;
            }
            player.displayClientMessage(Component.literal(text), false);
        } catch (Throwable t) {
            // 回显失败无所谓，结果仍写进了日志
        }
    }
}
