package org.sharedplayerdata.mcbridge;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *
 * <h3>为什么必须由「mod loading 完成」来触发，而不能在 mod 构造时启动</h3>
 * {@code @Mod} 的构造函数运行在 <b>mod loading 阶段</b>，此时 NeoForge / Forge
 * 正在用多个 worker 线程并行初始化各个 mod。如果这时候启动一个线程去调
 * {@code Minecraft.getInstance()}，就会从这条线程触发 Minecraft 类的加载与
 * 静态初始化——它会牵出一大片类，与并行加载中的 worker 互相持有对方的
 * 类初始化锁，形成 <b>JVM 类加载死锁</b>。表现就是游戏永远停在 Loading mods。
 * 大型整合包 mod 多、并行度高，几乎必然踩中；mod 少时可能侥幸不发作
 * （这正是 1.20.1 forge 正常、1.21.1 neoforge 卡死的原因）。
 *
 * <p>所以调用方必须在 {@code FMLLoadCompleteEvent} 之后再调 {@link #start()}。</p>
 */
public final class ClientScanner {

    /**
     * 等待客户端就绪的超时。给到 3 分钟：大型整合包在 FMLLoadComplete 之后还要
     * 加载大量资源，Minecraft 实例不一定马上就有；扫描线程是 daemon，多等一会
     * 没有任何副作用，放弃得太早反而会让本模组静默失效。
     */
    private static final long TIMEOUT_MILLIS = 180_000L;
    private static final long POLL_MILLIS = 250L;
    /**
     * 兜底延迟。只有当加载完成事件没能注册上时才走这条路：先睡这么久，
     * 确保 mod loading 早已结束，再开始触碰 Minecraft 类。
     */
    private static final long FALLBACK_DELAY_MILLIS = 60_000L;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private ClientScanner() {
    }

    /**
     * 启动扫描线程。可重复调用，但只会真正生效一次——事件回调和兜底线程
     * 可能都触发，不能因此开出两条扫描线程。
     */
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(ClientScanner::run, "SharedPlayerData-Init");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 兜底入口：延迟一段时间后再 {@link #start()}。
     * 仅在加载完成事件注册失败时使用，绝不作为常规路径。
     */
    public static void startDelayed() {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(FALLBACK_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            start();
        }, "SharedPlayerData-Fallback");
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
