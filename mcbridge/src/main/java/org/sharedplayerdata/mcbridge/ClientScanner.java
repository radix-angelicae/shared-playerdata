package org.sharedplayerdata.mcbridge;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.sharedplayerdata.core.CommandHook;
import org.sharedplayerdata.core.SpdLog;
import org.sharedplayerdata.core.SpdPlatform;
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
    /** 开始轮询前的缓冲，让主线程先把 Minecraft 初始化完。 */
    private static final long GRACE_MILLIS = 5000L;

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
        // 先等一小会儿再开始轮询。FMLLoadComplete 触发时，主线程往往正处于
        // Minecraft 类的构造 / 初始化过程中；此刻本线程若抢先调用
        // Minecraft.getInstance()，就会由本线程来触发 Minecraft 的静态初始化，
        // 与主线程互相持有类初始化锁而死锁。让主线程先跑一段，本线程就只会读到
        // 已经初始化好的实例，不再有竞争。
        try {
            Thread.sleep(GRACE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
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
                        if (SpdRuntime.disabled()) {
                            SpdLog.warn("已被 spd.disable 关闭，本次不做任何扫描");
                            return;
                        }
                        if (SpdPlatform.available()) {
                            // 扫描交给看门狗：它会先确认「确实停在主菜单」再动手。
                            // 这里不能立刻扫——此刻游戏可能正在加载 / 创建世界，
                            // 而加载期间 mc.level 为 null，直接扫就会和游戏抢
                            // level.dat，表现为进入世界时卡死。
                            // 命令注册也一并推迟到那时，避免在世界加载阶段
                            // 从本线程去初始化 Forge / NeoForge 的事件总线类。
                            SpdRuntime.startWatcher();
                        } else {
                            // 拿不到桥接层就没有看门狗，只能在这里补一次（启动时，安全）
                            CommandHook.tryRegister();
                            SpdRuntime.scan();
                        }
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
