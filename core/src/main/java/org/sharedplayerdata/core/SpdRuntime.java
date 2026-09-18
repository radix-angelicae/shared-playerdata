package org.sharedplayerdata.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    private static volatile Path gameDir;
    private static volatile UUID localUuid;
    private static volatile List<LevelDatFixer.WorldReport> lastReports =
            new ArrayList<LevelDatFixer.WorldReport>();
    private static volatile long lastScanMillis;

    private SpdRuntime() {
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
        for (LevelDatFixer.WorldReport r : reports) {
            if (r.result == LevelDatFixer.Result.FIXED) {
                fixed++;
            }
        }
        if (verbose || fixed > 0) {
            SpdLog.info("扫描完成（" + saves + "）：" + reports.size() + " 个存档，修正了 " + fixed + " 个");
        }
        return fixed;
    }

    /**
     * 后台看门狗：只要不在世界里（主菜单 / 标题界面），就定期重新扫描修正。
     *
     * <p>这样即使玩家是进入游戏之后才导入 / 新建的存档，回到主菜单后也会被修正，
     * 不必重启游戏。在世界中时不碰任何文件——那会儿游戏正在读写 level.dat。</p>
     *
     * <p>「是否在世界中」由 {@link SpdPlatform} 判断（桥接层的编译期代码）。
     * 绝不能用 {@code getField("level")} 这种字符串反射：混淆后字段名会变，
     * 在玩家机器上必然失败。</p>
     */
    public static void startWatcher() {
        if (!SpdPlatform.available()) {
            SpdLog.warn("桥接层未就绪，看门狗不启动（启动时的那次扫描仍然有效）");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        SpdPlatform p = SpdPlatform.get();
                        if (p == null || !p.isInWorld()) {
                            scan(false);
                        }
                    } catch (Throwable ignored) {
                        // 看门狗出错也不影响游戏
                    }
                    try {
                        Thread.sleep(15000L);
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
