package org.sharedplayerdata.mcbridge;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.sharedplayerdata.core.CommandHook;
import org.sharedplayerdata.core.SpdLog;
import org.sharedplayerdata.core.SpdRuntime;

/**
 * 在后台线程里等待客户端就绪，然后修正存档并长期看护。
 *
 * <p>只在客户端被加载（Forge / NeoForge 由各自的 ClientBootstrap 保证），
 * 所以可以放心引用 Minecraft 类。</p>
 */
public final class ClientScanner {

    private static final long TIMEOUT_MILLIS = 60_000L;
    private static final long POLL_MILLIS = 250L;

    private ClientScanner() {
    }

    public static void start() {
        Thread thread = new Thread(ClientScanner::run, "SharedPlayerData-Init");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    User user = mc.getUser();
                    UUID uuid = user == null ? null : user.getProfileId();
                    if (uuid != null) {
                        PlatformImpl.install();
                        SpdRuntime.init(mc.gameDirectory.toPath(), uuid);
                        CommandHook.tryRegister();
                        SpdRuntime.scan();
                        SpdRuntime.startWatcher();
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // 客户端还没准备好，继续等
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        SpdLog.warn("等待客户端就绪超时，本次未扫描存档");
    }
}
