package org.sharedplayerdata.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 运行期状态与操作入口。
 *
 * <p>保存游戏目录、当前玩家 UUID、最近一次扫描结果，并对外提供
 * 扫描 / 状态这两项能力。启动扫描与看门狗都走这里，{@code /spd} 命令只读它的结果。</p>
 */
public final class SpdRuntime {

    /** 看门狗轮询间隔。缩短到 1.5 秒，好让运行中导入的存档尽快被检测到。 */
    private static final long WATCH_POLL_MILLIS = 1500L;
    /** 连续读到几次「不在世界」才算真的停在主菜单（而不是正在加载世界）。 */
    private static final int SETTLE_ROUNDS = 2;

    private static volatile Path gameDir;
    private static volatile UUID localUuid;
    private static volatile List<LevelDatFixer.WorldReport> lastReports =
            new ArrayList<LevelDatFixer.WorldReport>();
    private static volatile long lastScanMillis;

    private SpdRuntime() {
    }

    /**
     * 总开关。出问题时可以一键让模组彻底停工，用来确认症状是否由它引起。
     *
     * <p>两种关法，任一即可：</p>
     * <ul>
     *   <li>启动参数加 {@code -Dsharedplayerdata.disable=true}</li>
     *   <li>在 .minecraft 目录里放一个名为 {@code spd.disable} 的空文件</li>
     * </ul>
     */
    public static boolean disabled() {
        if ("true".equalsIgnoreCase(System.getProperty("sharedplayerdata.disable", ""))) {
            return true;
        }
        try {
            Path g = gameDir;
            if (g != null && java.nio.file.Files.exists(g.resolve("spd.disable"))) {
                return true;
            }
        } catch (Throwable ignored) {
            // 读不到就当作没关
        }
        return false;
    }

    public static synchronized void init(Path gameDirectory, UUID uuid) {
        // 全零 UUID 是「还没拿到真实身份」的信号（离线启动时偶尔会在初始化
        // 早期出现）。用它去比对会把别人的数据当成「不是我的」而误删，
        // 结果就是所有人进存档都变空号——所以这里直接拒绝。
        if (uuid != null && Uuids.isNil(uuid)) {
            SpdLog.warn("拿到的是全零 UUID，拒绝初始化（" + uuid + "）");
            gameDir = gameDirectory;
            return;
        }
        gameDir = gameDirectory;
        localUuid = uuid;
    }

    public static Path gameDir() {
        return gameDir;
    }

    public static UUID localUuid() {
        return localUuid;
    }

    public static Path savesDir() {
        return SharedPlayerData.resolveSavesDir(gameDir);
    }

    /**
     * 存档目录的轻量指纹：每个世界的名字 + 它 {@code level.dat} 的「mtime/size」。
     *
     * <p>只列目录、只 stat level.dat，不解压任何 NBT，所以每 1.5 秒算一次几乎零开销。
     * 世界按名字排序，否则 {@link DirectoryStream} 的不确定顺序会让指纹每轮都变，
     * 变成每轮都扫描。</p>
     *
     * <p>这个指纹是「什么时候需要扫描」的唯一依据：存档没变就不用扫，变了才扫。
     * 比固定周期盲扫更灵敏（刚导入就能触发），也更安全（不会在加载世界的空窗里
     * 反复去碰文件）。</p>
     *
     * @return null 表示目录暂时读不到（还没建好 / 正被占用），调用方应保持上一轮的值
     */
    private static String savesFingerprint() {
        Path saves = savesDir();
        if (saves == null || !Files.isDirectory(saves)) {
            return null;
        }
        List<Path> worlds = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(saves)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    worlds.add(child);
                }
            }
        } catch (IOException e) {
            return null;
        }
        // 排序是必须的，见方法注释
        java.util.Collections.sort(worlds, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return a.getFileName().toString().compareTo(b.getFileName().toString());
            }
        });
        StringBuilder sb = new StringBuilder();
        for (Path world : worlds) {
            sb.append(world.getFileName().toString()).append(':');
            Path levelDat = world.resolve("level.dat");
            try {
                if (Files.isRegularFile(levelDat)) {
                    sb.append(Files.getLastModifiedTime(levelDat).toMillis())
                            .append('/').append(Files.size(levelDat));
                } else {
                    sb.append('-'); // 正在创建的世界，level.dat 还没出现
                }
            } catch (IOException e) {
                sb.append('?');
            }
            sb.append('|');
        }
        return sb.toString();
    }

    /** 最近一次扫描结果（可能是空列表）。 */
    public static List<LevelDatFixer.WorldReport> lastReports() {
        return lastReports;
    }

    /** 重新扫描并修正所有存档。 */
    public static int scan() {
        return scan(true);
    }

    /** {@code verbose=false} 时只在真的改了东西才写日志，供后台轮询使用。 */
    public static synchronized int scan(boolean verbose) {
        if (disabled()) {
            if (verbose) {
                SpdLog.warn("已通过 spd.disable / -Dsharedplayerdata.disable=true 关闭，跳过扫描");
            }
            return 0;
        }
        Path saves = savesDir();
        UUID uuid = localUuid;
        if (saves == null || !Files.isDirectory(saves)) {
            if (verbose) {
                SpdLog.warn("找不到存档目录，跳过扫描（gameDir=" + gameDir + "）");
            }
            return 0;
        }
        if (uuid == null) {
            if (verbose) {
                SpdLog.warn("无法获取当前登录玩家的 UUID，跳过扫描");
            }
            return 0;
        }
        List<LevelDatFixer.WorldReport> reports = LevelDatFixer.scan(saves, uuid);
        lastReports = reports;
        lastScanMillis = System.currentTimeMillis();
        int fixed = 0;
        int skipped = 0;
        for (LevelDatFixer.WorldReport r : reports) {
            if (r.result == LevelDatFixer.Result.FIXED) {
                fixed++;
            } else if (r.result == LevelDatFixer.Result.SKIPPED) {
                skipped++;
            }
        }
        // 每轮都打汇总。之前只在 fixed>0 时打，导致「扫了但全被跳过」时
        // 日志里什么都没有，用户完全看不出模组有没有工作。
        SpdLog.info("扫描完成（" + saves + "）：" + reports.size() + " 个存档，"
                + "修正 " + fixed + " 个，跳过 " + skipped + " 个"
                + "（我的 UUID=" + uuid + "）");
        // 非 CLEAN 的世界单独报一行，把原因写清楚，便于确认「数据切换」到底有没有发生
        for (LevelDatFixer.WorldReport r : reports) {
            if (r.result == LevelDatFixer.Result.CLEAN) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append("  [").append(r.result).append("] ").append(r.name)
                    .append("  内嵌数据=")
                    .append(r.embeddedOwner == null ? "无" : r.embeddedOwner.toString())
                    .append("  我的数据=").append(r.localHasData ? "有" : "无");
            for (String note : r.notes) {
                line.append("  | ").append(note);
            }
            SpdLog.info(line.toString());
        }
        return fixed;
    }

    /**
     * 后台看门狗：<b>存档目录真的变了</b>，且<b>确认停在主菜单</b>时才扫描修正。
     *
     * <h3>为什么用「存档指纹变化」来触发</h3>
     * 三种触发方式各有坑，这里逐个排除：
     * <ul>
     *   <li><b>固定周期盲扫</b>（旧版每 15 秒）：进入 / 创建世界的过程中
     *       {@code mc.level} 有相当长一段是 null，会被误当成「在主菜单」，
     *       于是扫描线程和游戏同时读写同一个 level.dat → 卡在「正在进入世界」。</li>
     *   <li><b>只认「启动首次 + 退出世界后」</b>：游戏运行中才把存档复制进 saves、
     *       然后直接点进去时，两个时机都不满足 → 修正完全不发生。</li>
     *   <li><b>按 level.dat 的「mtime 距今几秒」判断</b>：退出世界时游戏必然刚保存过
     *       level.dat，这条保护会把「退出后补扫」每次都跳过；刚复制进来的存档同理。
     *       已整段删除。</li>
     * </ul>
     *
     * <p>现在只认一个信号：{@link #savesFingerprint()} 变了（新世界出现、某个
     * level.dat 被写入）。存档没变就完全不碰文件，天然限频；变了就尽快扫。
     * 再加一道<b>静止闸</b>：连续 {@link #SETTLE_ROUNDS} 次读到「不在世界」才动手，
     * 避开世界加载中的 null 窗口。</p>
     *
     * <p>最后一道防线在 {@link LevelDatFixer}：写回前重新校验 level.dat 指纹，
     * 读之后被游戏改过就放弃本轮——精确，且不会误杀正常的修正。</p>
     *
     * <p>「是否在世界中」由 {@link SpdPlatform} 判断（桥接层的编译期代码）。
     * 绝不能用 {@code getField("level")} 这种字符串反射：混淆后字段名会变，
     * 在玩家机器上必然失败。</p>
     */
    public static void startWatcher() {
        if (!SpdPlatform.available()) {
            SpdLog.warn("桥接层未就绪，看门狗不启动（调用方会改用启动时的一次性扫描）");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String lastFingerprint = null;
                boolean needsScan = true; // 启动后先扫一次
                int idleRounds = 0;
                while (true) {
                    try {
                        if (disabled()) {
                            return; // 被手动关闭，安静退出
                        }

                        String fingerprint = savesFingerprint();
                        if (fingerprint != null && !fingerprint.equals(lastFingerprint)) {
                            needsScan = true;
                            lastFingerprint = fingerprint;
                        }

                        SpdPlatform p = SpdPlatform.get();
                        boolean inWorld = p != null && p.isInWorld();
                        if (inWorld) {
                            idleRounds = 0;
                        } else {
                            idleRounds++;
                        }

                        if (idleRounds >= SETTLE_ROUNDS && needsScan) {
                            needsScan = false;
                            // 命令注册放在这里而不是启动时：此刻已确认停在主菜单，
                            // 不会在世界加载阶段从后台线程去初始化事件总线相关的类。
                            CommandHook.tryRegister();
                            scan(false);
                            // 扫描本身会写回 level.dat，立即重算指纹，
                            // 免得下一轮把自己的改动当成「存档又变了」而多扫一次。
                            String after = savesFingerprint();
                            if (after != null) {
                                lastFingerprint = after;
                            }
                        }
                    } catch (Throwable ignored) {
                        // 看门狗出错也不影响游戏
                    }
                    try {
                        Thread.sleep(WATCH_POLL_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "SharedPlayerData-Watch");
        t.setDaemon(true);
        t.start();
    }

    /** 只清点不修改。 */
    public static synchronized List<LevelDatFixer.WorldReport> inspect() {
        Path saves = savesDir();
        UUID uuid = localUuid;
        if (saves == null || !Files.isDirectory(saves) || uuid == null) {
            return new ArrayList<LevelDatFixer.WorldReport>();
        }
        return LevelDatFixer.inspectOnly(saves, uuid);
    }

    // ------------------------------------------------------------------ 命令输出

    /** {@code /spd status} */
    public static List<String> status() {
        List<String> out = new ArrayList<String>();
        UUID uuid = localUuid;
        out.add("== Shared Player Data ==");
        out.add("我的 UUID: " + (uuid == null ? "<未知>" : uuid.toString()));
        Path saves = savesDir();
        out.add("存档目录: " + (saves == null ? "<未找到>" : saves.toString()));
        if (lastScanMillis > 0) {
            out.add("上次扫描: " + new SimpleDateFormat("HH:mm:ss").format(new Date(lastScanMillis)));
        }
        out.add("");
        List<LevelDatFixer.WorldReport> reports = inspect();
        if (reports.isEmpty()) {
            out.add("（没有找到任何存档）");
            return out;
        }
        for (LevelDatFixer.WorldReport r : reports) {
            out.add("· " + r.name);
            out.add("    内嵌数据: " + (r.embeddedOwner == null ? "无"
                    : r.embeddedOwner.toString() + (r.embeddedOwner.equals(uuid) ? "（是你的）" : "（别人的）")));
            out.add("    我的数据: " + (r.localHasData ? "有" : "★ 没有（进去会是空号）"));
            out.add("    数据文件: " + r.availableUuids.size() + " 份");
            for (PlayerDataStore.Entry e : PlayerDataStore.list(r.dir)) {
                if (e.orphan) {
                    out.add("      - 孤儿归档（无 UUID，无法自动归属）");
                    continue;
                }
                String who = e.uuid.equals(uuid) ? "你" : "他人";
                String empty = LevelDatFixer.isProbablyEmptyCached(e.file) ? "，空号" : "";
                out.add("      - " + e.uuid + " (" + who + empty + ")");
            }
            for (String note : r.notes) {
                out.add("    ! " + note);
            }
        }
        return out;
    }

    /** {@code /spd list} —— 列出每个存档里现存的玩家数据文件。 */
    public static List<String> listData() {
        List<String> out = new ArrayList<String>();
        Path saves = savesDir();
        if (saves == null || !Files.isDirectory(saves)) {
            out.add("找不到存档目录");
            return out;
        }
        UUID me = localUuid;
        boolean any = false;
        for (LevelDatFixer.WorldReport r : inspect()) {
            List<PlayerDataStore.Entry> entries = PlayerDataStore.list(r.dir);
            if (entries.isEmpty()) {
                continue;
            }
            any = true;
            out.add("== " + r.name + " ==");
            for (PlayerDataStore.Entry e : entries) {
                String who = e.orphan ? "<孤儿归档>" : e.uuid.toString();
                String mark = !e.orphan && e.uuid.equals(me) ? "  ← 这是你" : "";
                if (e.orphan) {
                    mark = "  ← 无可归属的 UUID";
                }
                out.add("  " + who + "  " + formatSize(e.size) + "  "
                        + formatTime(e.modifiedMillis) + mark);
            }
        }
        if (!any) {
            out.add("所有存档里都没有玩家数据文件（playerdata 目录为空）。");
        }
        return out;
    }

    // ------------------------------------------------------------------ 格式化

    private static String formatSize(long size) {
        if (size < 0) {
            return "?";
        }
        if (size < 1024) {
            return size + "B";
        }
        return (size / 1024) + "KB";
    }

    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "?";
        }
        return new SimpleDateFormat("MM-dd HH:mm").format(new Date(millis));
    }
}
