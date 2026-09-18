package org.sharedplayerdata.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 核心修正逻辑：把「房主的玩家数据」从单人世界的 {@code level.dat} 里摘出来，
 * 并归档成一份独立的玩家数据文件。
 *
 * <h3>背景</h3>
 * Java 版 1.21.11 及更早，单人世界会把世界所有者的完整玩家数据额外存一份到
 * {@code level.dat → Data → Player}。单人模式打开世界时游戏<b>无条件优先使用这份数据</b>，
 * 完全不看当前玩家 UUID。于是房主把存档发给别人后，接手者打开会变成房主的背包，
 * 甚至把自己原本的 {@code playerdata/<UUID>.dat} 覆盖掉。
 *
 * <h3>本类的做法</h3>
 * <ol>
 *   <li>发现 {@code Data → Player} 属于别人 → <b>先归档</b>成
 *       {@code playerdata/<那个UUID>.dat}，再删掉这个标签。
 *       「先归档」是关键：老存档第一次装上本模组时，房主自己的数据不会丢，
 *       他之后打开这个世界会正常拿回自己的东西。</li>
 *   <li>删掉之后游戏就退回按 UUID 读取 {@code playerdata/<自己的UUID>.dat}。</li>
 *   <li>如果<b>你</b>在这个世界里没有数据文件，会在报告里明确列出世界里现存的
 *       其他 UUID——这时候多半是 UUID 对不上（正版 / 离线账号算法不同），
 *       用 {@code /spd adopt <uuid>} 把属于你的那份认领回来。</li>
 * </ol>
 *
 * <p>Minecraft 26.1 起官方改用 {@code Data → singleplayer_uuid} 指向
 * {@code players/data/<UUID>.dat}，本类同样处理。</p>
 */
public final class LevelDatFixer {

    public static final String BACKUP_SUFFIX = ".spd_backup";

    /** 内嵌数据没记录 UUID 时的兜底归档文件名（无法用 UUID 命名）。 */
    public static final String ORPHAN_FILE = "spd-orphan.dat";

    private static final String TAG_DATA = "Data";
    private static final String TAG_PLAYER = "Player";
    private static final String TAG_SINGLEPLAYER_UUID = "singleplayer_uuid";
    private static final String TAG_UUID = "UUID";
    private static final String TAG_LEVEL_NAME = "LevelName";

    private LevelDatFixer() {
    }

    /** 单个存档的扫描结果。 */
    public enum Result {
        /** 世界本来就是自己的，或没有需要修正的内容。 */
        CLEAN,
        /** 已清除他人数据。 */
        FIXED,
        /** level.dat 缺失或无法解析，已跳过。 */
        SKIPPED
    }

    /** 一个存档的诊断信息，供游戏内命令展示。 */
    public static final class WorldReport {
        public final Path dir;

        /**
         * 展示用的世界名。
         *
         * <p>刻意<b>不是</b> final：初始值是文件夹名，读到 level.dat 之后会用真正的
         * {@code LevelName} 覆盖它——两者可能不一致（比如玩家重命名过存档）。</p>
         */
        public String name;
        public Result result = Result.SKIPPED;
        public UUID embeddedOwner;
        public boolean archived;
        public boolean localHasData;
        public final List<UUID> availableUuids = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();

        WorldReport(Path dir, String name) {
            this.dir = dir;
            this.name = name;
        }

        /** 浅拷贝一份，避免调用方改到缓存里的对象。 */
        WorldReport copy() {
            WorldReport r = new WorldReport(dir, name);
            r.result = result;
            r.embeddedOwner = embeddedOwner;
            r.archived = archived;
            r.localHasData = localHasData;
            r.availableUuids.addAll(availableUuids);
            r.notes.addAll(notes);
            return r;
        }
    }

    // ------------------------------------------------------------------ 扫描缓存

    /**
     * 扫描缓存：key 是世界目录，value 是「上次扫描时的文件指纹 + 报告」。
     *
     * <p>看门狗每 15 秒扫一次全量存档，每次都解压并解析一遍 level.dat 在存档多的时候
     * 会明显卡顿。而 level.dat 绝大多数时间根本没变，所以指纹相同就直接复用上次的结果。</p>
     *
     * <p>指纹 = 最后修改时间 + 文件大小。模组自己写回文件后指纹必然变化，
     * 所以不会出现「改了却还用旧结果」的情况。</p>
     */
    private static final java.util.Map<Path, CacheEntry> SCAN_CACHE =
            new java.util.concurrent.ConcurrentHashMap<Path, CacheEntry>();

    private static final class CacheEntry {
        final long stamp;
        final WorldReport report;

        CacheEntry(long stamp, WorldReport report) {
            this.stamp = stamp;
            this.report = report;
        }
    }

    private static long stampOf(Path levelDat) {
        try {
            return Files.getLastModifiedTime(levelDat).toMillis() * 31L + Files.size(levelDat);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 供外部（比如改了存档之后）清空缓存。 */
    public static void clearCache() {
        SCAN_CACHE.clear();
    }

    /** 扫描整个 saves 目录，返回每个存档的报告。 */
    public static List<WorldReport> scan(Path savesDir, UUID localUuid) {
        List<WorldReport> reports = new ArrayList<>();
        if (Uuids.isNil(localUuid)) {
            // 全零 UUID 不是合法身份。拿它去比对，任何内嵌数据都会被判成
            // 「别人的」而删掉，结果是所有人进存档都变空号——宁可什么都不做。
            SpdLog.warn("拿到的是全零 UUID，拒绝扫描（" + localUuid + "）");
            return reports;
        }
        List<Path> worlds = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    worlds.add(child);
                }
            }
        } catch (IOException e) {
            SpdLog.error("无法读取存档目录 " + savesDir, e);
            return reports;
        }
        for (Path world : worlds) {
            try {
                reports.add(inspectAndFix(world, localUuid));
            } catch (Throwable t) {
                SpdLog.error("扫描存档 " + world.getFileName() + " 时出错", t);
            }
        }
        pruneCache(worlds, savesDir);
        return reports;
    }

    /** 只清点不修改，用于 {@code /spd status}。 */
    public static List<WorldReport> inspectOnly(Path savesDir, UUID localUuid) {
        List<WorldReport> reports = new ArrayList<>();
        if (Uuids.isNil(localUuid)) {
            SpdLog.warn("拿到的是全零 UUID，拒绝清点（" + localUuid + "）");
            return reports;
        }
        List<Path> worlds = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    worlds.add(child);
                }
            }
        } catch (IOException e) {
            SpdLog.error("无法读取存档目录 " + savesDir, e);
            return reports;
        }
        for (Path world : worlds) {
            try {
                reports.add(inspectAndFix(world, localUuid, false));
            } catch (Throwable t) {
                SpdLog.error("扫描存档 " + world.getFileName() + " 时出错", t);
            }
        }
        pruneCache(worlds, savesDir);
        return reports;
    }

    /**
     * 清掉已经不存在的世界对应的缓存条目。
     *
     * <p>缓存是静态的，玩家删掉存档后条目会永久残留（内存泄漏，
     * 且万一以后建了同名目录还会命中一份过期的报告）。</p>
     */
    private static void pruneCache(List<Path> currentWorlds, Path savesDir) {
        if (SCAN_CACHE.isEmpty()) {
            return;
        }
        java.util.Set<Path> live = new java.util.HashSet<Path>(currentWorlds);
        java.util.Iterator<Path> it = SCAN_CACHE.keySet().iterator();
        while (it.hasNext()) {
            Path p = it.next();
            // 只清理属于本次 saves 目录的条目，避免误删别处的
            boolean inScope = savesDir != null && p.getParent() != null
                    && p.getParent().equals(savesDir);
            if (inScope && !live.contains(p)) {
                it.remove();
            } else if (!Files.isDirectory(p)) {
                it.remove();
            }
        }
    }

    /** 修正单个世界目录。 */
    public static Result fixWorld(Path worldDir, UUID localUuid) {
        return inspectAndFix(worldDir, localUuid, true).result;
    }

    private static WorldReport inspectAndFix(Path worldDir, UUID localUuid) {
        return inspectAndFix(worldDir, localUuid, true);
    }

    private static WorldReport inspectAndFix(Path worldDir, UUID localUuid, boolean apply) {
        WorldReport report = new WorldReport(worldDir, worldDir.getFileName().toString());
        Path levelDat = worldDir.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            report.result = Result.SKIPPED;
            return report;
        }

        // 指纹没变就直接复用上次的报告（看门狗高频扫描时的关键优化）。
        // 只有上次的结论是 CLEAN 才敢复用：FIXED 意味着这次刚刚改过文件，
        // 复用的话 result 会一直停在 FIXED，/spd status 会一直显示「内嵌数据是别人的」，
        // 而且 SpdRuntime.scan() 会把缓存里的 FIXED 重复计入「修正了 N 个」。
        long stamp = stampOf(levelDat);
        if (stamp >= 0) {
            CacheEntry cached = SCAN_CACHE.get(worldDir);
            if (cached != null && cached.stamp == stamp
                    && cached.report.result == Result.CLEAN) {
                WorldReport fresh = cached.report.copy();
                fresh.localHasData = PlayerDataStore.has(worldDir, localUuid);
                return fresh;
            }
        }

        Tag root;
        try {
            root = NbtIo.readCompressed(levelDat);
        } catch (Throwable t) {
            report.notes.add("level.dat 无法解析，已跳过");
            report.result = Result.SKIPPED;
            return report;
        }
        if (root == null || !root.isCompound()) {
            report.result = Result.SKIPPED;
            return report;
        }
        Tag data = root.getCompound(TAG_DATA);
        if (data == null) {
            report.result = Result.SKIPPED;
            return report;
        }

        report.name = data.getString(TAG_LEVEL_NAME, report.name);

        // 当前玩家在这个世界里有没有自己的数据
        report.localHasData = PlayerDataStore.has(worldDir, localUuid);
        for (PlayerDataStore.Entry e : PlayerDataStore.list(worldDir)) {
            report.availableUuids.add(e.uuid);
        }

        // 只要这个世界里有自己的数据，就先备份一份（只做一次，保留最原始那份）。
        // 这是防丢数据的最后一道防线：哪怕后面判断出错误删了什么，
        // 你自己的原始数据也还在 .spd_backup 里。与本次是否真的修正无关。
        if (apply && report.localHasData) {
            backupOwnPlayerData(worldDir, localUuid);
        }

        boolean changed = false;
        StringBuilder reasons = new StringBuilder();

        // ---- 26.1+ ：Data → singleplayer_uuid（int[4]） ----
        if (data.contains(TAG_SINGLEPLAYER_UUID)) {
            UUID owner = Uuids.fromIntArray(data.getIntArray(TAG_SINGLEPLAYER_UUID));
            if (owner == null) {
                report.notes.add("singleplayer_uuid 格式损坏，已跳过");
            } else {
                report.embeddedOwner = owner;
                if (!owner.equals(localUuid)) {
                    // 这里只删「指针」，数据本体应该在 players/data/<owner>.dat。
                    // 必须确认那份文件真的在：指针一删，如果数据没落地就再也找不回了。
                    boolean safe = !apply || ownerDataExists(worldDir, owner, report);
                    if (safe) {
                        // 只在真的要落盘时才改内存里的 tag（inspectOnly 必须保持只读，
                        // 否则将来有人给它加上写回就会变成破坏性操作）
                        if (apply) {
                            data.remove(TAG_SINGLEPLAYER_UUID);
                        }
                        changed = true;
                        reasons.append("singleplayer_uuid(").append(owner).append(") ");
                    } else {
                        report.notes.add("players/data 里找不到 " + owner
                                + " 的数据，已保留 singleplayer_uuid（不删，以免数据失联）");
                    }
                }
            }
        }

        // ---- 1.21.11 及更早：Data → Player（完整玩家数据） ----
        if (data.contains(TAG_PLAYER)) {
            Tag player = data.get(TAG_PLAYER);
            UUID owner = player != null && player.isCompound()
                    ? Uuids.fromIntArray(player.getIntArray(TAG_UUID))
                    : null;
            if (owner == null) {
                // 老存档里 Player 可能没写 UUID，无法判断归属。
                // 只要自己在这个世界里有独立数据，就以那份为准。
                if (report.localHasData) {
                    // 只有确认这份内嵌数据已经安全落地（归档成功），才允许删除。
                    // 归档失败就保留内嵌数据——宁可不动，也不能把数据删没了。
                    if (!apply || archiveEmbedded(worldDir, player, null, report)) {
                        if (apply) {
                            report.archived = true;
                            data.remove(TAG_PLAYER);
                        }
                        changed = true;
                        reasons.append("Player(未记录 UUID，已归档) ");
                    } else {
                        report.notes.add("归档失败，已保留 level.dat 内嵌数据（未删除）");
                    }
                } else {
                    report.notes.add("level.dat 内嵌玩家数据未记录 UUID，"
                            + "且你在此世界没有独立数据文件，已保留（避免把存档变成空号）");
                }
            } else {
                report.embeddedOwner = owner;
                if (!owner.equals(localUuid)) {
                    // 26.1 分支：只删指针，数据本体应该在 players/data/<owner>.dat。
                    // 删之前必须确认那份文件真的在——否则指针一删，
                    // 这份数据就再也没人能读到了。
                    boolean safeToRemove;
                    if (!apply) {
                        safeToRemove = true;
                    } else {
                        safeToRemove = dataIsSafeOnDisk(worldDir, owner, player, report);
                    }
                    if (safeToRemove) {
                        if (apply) {
                            data.remove(TAG_PLAYER);
                        }
                        changed = true;
                        reasons.append("Player(").append(owner).append(") ");
                    } else {
                        report.notes.add("无法把内嵌数据安全落地，已保留（未删除），以免数据丢失");
                    }
                }
            }
        }

        if (!changed) {
            report.result = Result.CLEAN;
            if (apply && stamp >= 0) {
                SCAN_CACHE.put(worldDir, new CacheEntry(stamp, report.copy()));
            }
        } else if (!apply) {
            report.result = Result.FIXED;
        } else {
            // 自己的数据已在上面备份过，这里直接写回
            try {
                writeAtomically(root, levelDat);
                report.result = Result.FIXED;
                // FIXED 不进缓存：这次刚改过文件，embeddedOwner 还是改之前的值，
                // 缓存下来会让 /spd status 一直显示旧状态、scan() 重复计数。
                SCAN_CACHE.remove(worldDir);
                SpdLog.info("已清除存档「" + report.name + "」中属于其他玩家的数据：" + reasons
                        + "→ 现在会以你自己的 UUID(" + localUuid + ") 读取玩家数据");
            } catch (IOException e) {
                SpdLog.error("写回 level.dat 失败：" + levelDat, e);
                report.notes.add("写回 level.dat 失败");
                report.result = Result.SKIPPED;
            }
        }

        // 关键诊断：删完之后你在这个世界里并没有数据文件 → 进去必然是空号
        if (!report.localHasData && report.result != Result.SKIPPED) {
            StringBuilder hint = new StringBuilder();
            hint.append("你(UUID ").append(localUuid).append(") 在这个世界里没有玩家数据文件，")
                    .append("直接进去会是空号。");
            if (!report.availableUuids.isEmpty()) {
                hint.append(" 该世界现存 ").append(report.availableUuids.size())
                        .append(" 份数据，用 /spd list 查看，再用 /spd adopt <uuid> 认领属于你的那份");
                if (report.availableUuids.size() <= 4) {
                    hint.append("：");
                    for (UUID u : report.availableUuids) {
                        hint.append(' ').append(u);
                    }
                }
            }
            report.notes.add(hint.toString());
            if (apply) {
                SpdLog.warn("存档「" + report.name + "」：" + hint);
            }
        }
        return report;
    }

    /**
     * 把 {@code level.dat} 里内嵌的那份玩家数据另存为独立的
     * {@code playerdata/<UUID>.dat}，避免数据被白白丢掉。
     *
     * <p>目标文件已存在时不覆盖——已存在的那份通常更新、更完整。</p>
     */
    private static boolean archiveEmbedded(Path worldDir, Tag player, UUID owner, WorldReport report) {
        if (player == null || !player.isCompound()) {
            return false;
        }
        try {
            if (owner == null) {
                // 没有 UUID 就没法命名文件，塞进一个固定名字的兜底文件
                Path dir = PlayerDataStore.dirOf(worldDir);
                Files.createDirectories(dir);
                Path target = dir.resolve(ORPHAN_FILE);
                if (Files.exists(target)) {
                    // 已经归档过一份了，那份就是安全副本
                    report.notes.add("已存在兜底归档 " + ORPHAN_FILE + "，未覆盖");
                    return true;
                }
                NbtIo.writeCompressed(player, target);
                report.notes.add("内嵌数据未记录 UUID，已存为 " + ORPHAN_FILE);
                return true;
            }
            Path dir = PlayerDataStore.dirOf(worldDir);
            Files.createDirectories(dir);
            Path target = dir.resolve(owner + ".dat");
            if (Files.exists(target)) {
                // 目标已存在：只有在它看起来是「空号数据」时才用内嵌数据覆盖。
                // 这一点很关键——单人世界里 MC 有时会给房主留一份空的 playerdata，
                // 无条件跳过归档，房主之后从服务器进这个世界就永远是空号。
                Tag existing;
                try {
                    existing = NbtIo.readCompressed(target);
                } catch (Throwable t) {
                    existing = null;
                }
                if (!isProbablyEmpty(existing)) {
                    // 目标里已经有真数据，内嵌这份删掉也无妨
                    return true;
                }
                backupOnce(target);
                NbtIo.writeCompressed(player, target);
                SpdLog.info("存档「" + report.name + "」里 " + target.getFileName()
                        + " 是空号数据，已用 level.dat 内嵌的数据覆盖（原件备份为 .spd_backup）");
                return true;
            }
            NbtIo.writeCompressed(player, target);
            SpdLog.info("已把存档「" + report.name + "」内嵌的玩家数据归档为 "
                    + target.getParent().getFileName() + "/" + target.getFileName());
            return true;
        } catch (Throwable t) {
            SpdLog.warn("归档内嵌玩家数据失败：" + t);
            report.notes.add("归档内嵌玩家数据失败：" + t);
            return false;
        }
    }

    /**
     * 26.1 格式：确认 {@code players/data/<owner>.dat}（或旧版 playerdata）真的存在且能读。
     *
     * <p>不存在就说明这份数据还没落地，此时不能删指针。</p>
     */
    private static boolean ownerDataExists(Path worldDir, UUID owner, WorldReport report) {
        if (PlayerDataStore.has(worldDir, owner)) {
            return true;
        }
        // 数据文件不在：把 report 里的提示补上，方便 /spd status 展示
        report.notes.add("注意：磁盘上没有 " + owner + " 的数据文件");
        return false;
    }

    /**
     * 判断内嵌的那份玩家数据是否已经「安全落地」——即磁盘上另有一份可用的副本。
     *
     * <p>只有返回 true 时才允许把 {@code Data/Player} 删掉。归档失败（磁盘满、
     * 权限问题等）一律返回 false，此时保留内嵌数据，宁可这次不修，也不能丢数据。</p>
     */
    private static boolean dataIsSafeOnDisk(Path worldDir, UUID owner, Tag player, WorldReport report) {
        if (!archiveEmbedded(worldDir, player, owner, report)) {
            return false;
        }
        report.archived = true;
        // 归档函数成功返回后，磁盘上必然有一份；再校验一次文件真的能读回来
        Path file = PlayerDataStore.dirOf(worldDir).resolve(owner + ".dat");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            return NbtIo.readCompressed(file) != null;
        } catch (Throwable t) {
            SpdLog.warn("归档后校验失败：" + t);
            return false;
        }
    }

    /**
     * 判断一份玩家数据是不是「空号」（刚创建、什么都没有）。
     *
     * <p>判据是「有任何一项偏离了新玩家的默认值，就算玩过」。
     * 只看背包会误判——一个刚回家、空手、0 级的玩家背包也是空的，
     * 但他显然不是空号，用内嵌数据覆盖掉他的进度就糟了。</p>
     *
     * <p>检查项：</p>
     * <ul>
     *   <li>Inventory / EnderItems 非空</li>
     *   <li>XpLevel / XpTotal / XpP 任一大于 0</li>
     *   <li>Health 不等于满血 20</li>
     *   <li>foodLevel 不等于 20</li>
     *   <li>SelectedItemSlot 不是默认的第一格</li>
     *   <li>有 ActiveEffects（药水效果）</li>
     *   <li>abilities 被改过（创造 / 飞行 / 不能建造）</li>
     *   <li>air / fire / fallDistance 等非默认值</li>
     * </ul>
     */
    static boolean isProbablyEmpty(Tag playerData) {
        if (playerData == null || !playerData.isCompound()) {
            return true;
        }
        // 先做「完整性检查」：Minecraft 给一个真正的新玩家写数据时，
        // 下面这些字段一定都会写进去。缺了就说明这份数据不完整——
        // 可能是写入中断、磁盘故障，或者是别的模组写的非标准格式。
        //
        // 不完整的数据绝不能当成空号：拿 level.dat 里的内嵌数据覆盖它，
        // 等于在玩家无感知的情况下毁掉一份我们根本没看懂的数据。
        if (!looksLikeCompletePlayerData(playerData)) {
            SpdLog.warn("玩家数据不完整（缺少关键字段），按「非空号」处理，不做覆盖");
            return false;
        }
        // 1. 容器类：非空就一定玩过
        if (listNotEmpty(playerData, "Inventory") || listNotEmpty(playerData, "EnderItems")) {
            return false;
        }
        // 2. 经验：等级 / 累计经验 / 经验条
        if (intGreaterThan(playerData, "XpLevel", 0) || intGreaterThan(playerData, "XpTotal", 0)
                || floatGreaterThan(playerData, "XpP", 0.0f)) {
            return false;
        }
        // 3. 血量：满血 20 是新玩家默认值，不等于它就说明有过战斗或增益
        if (floatNotEqual(playerData, "Health", 20.0f) || floatNotEqual(playerData, "health", 20.0f)) {
            return false;
        }
        // 4. 饱食度
        if (intNotEqual(playerData, "foodLevel", 20) || floatNotEqual(playerData, "foodSaturationLevel", 5.0f)) {
            return false;
        }
        // 5. 选中物品栏（默认 0）
        if (intNotEqual(playerData, "SelectedItemSlot", 0) || intNotEqual(playerData, "selectedItemSlot", 0)) {
            return false;
        }
        // 6. 药水效果
        if (listNotEmpty(playerData, "ActiveEffects") || listNotEmpty(playerData, "active_effects")) {
            return false;
        }
        // 7. 能力被改过（创造模式、飞行、冒险模式不能建造）
        Tag abilities = playerData.get("abilities");
        if (abilities != null && abilities.isCompound()) {
            if (boolIsTrue(abilities, "flying") || boolIsTrue(abilities, "instabuild")
                    || boolIsFalse(abilities, "mayBuild")) {
                return false;
            }
        }
        // 8. 其它一旦玩过就很难保持默认的值
        if (floatNotEqual(playerData, "FallDistance", 0.0f) || intNotEqual(playerData, "fire", 0)
                || intNotEqual(playerData, "Fire", -20) || intNotEqual(playerData, "Air", 300)
                || intNotEqual(playerData, "air", 300) || intNotEqual(playerData, "SleepTimer", 0)) {
            return false;
        }
        return true;
    }

    /**
     * 判断一份玩家数据是否「结构完整」。
     *
     * <p>用途：把「新玩家的完整空数据」和「残缺/损坏的数据」区分开。
     * 只有完整且各项都是默认值的数据才叫空号，才允许被内嵌数据覆盖。</p>
     *
     * <p>判定方式：给出若干组「必定存在的字段」，每组只要有一个存在就算这一组通过；
     * 全部组都通过才认为完整。用分组是因为不同 MC 版本字段名有大小写差异
     * （比如 {@code Health} / {@code health}、{@code Pos} / {@code pos}）。</p>
     */
    private static final String[][] REQUIRED_FIELD_GROUPS = {
            {"Pos", "pos"},                       // 位置，任何存档过的玩家都有
            {"Rotation", "rotation"},             // 朝向
            {"Inventory", "inventory"},           // 背包（空也算，但字段在）
            {"Health", "health"},                 // 血量
    };

    private static boolean looksLikeCompletePlayerData(Tag playerData) {
        for (String[] group : REQUIRED_FIELD_GROUPS) {
            boolean found = false;
            for (String key : group) {
                if (playerData.get(key) != null) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 空号判断缓存

    /**
     * 「这个文件是不是空号」的缓存，key 是文件路径，value 是「文件指纹 + 结论」。
     *
     * <p>{@code /spd status} 会为每个存档里的每一份数据各读一次完整 NBT 来判断
     * 是不是空号。存档多、数据份数多的时候，这在 Brigadier 主线程上是明显卡顿的。
     * 玩家数据文件绝大多数时间不变，所以按「修改时间 + 大小」做指纹缓存。</p>
     *
     * <p>容量有上限，超了就整体清空——这是个纯加速用的缓存，丢了只是慢一点。</p>
     */
    private static final int EMPTY_CACHE_LIMIT = 512;
    private static final java.util.Map<Path, EmptyCacheEntry> EMPTY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<Path, EmptyCacheEntry>();

    private static final class EmptyCacheEntry {
        final long stamp;
        final boolean empty;

        EmptyCacheEntry(long stamp, boolean empty) {
            this.stamp = stamp;
            this.empty = empty;
        }
    }

    /** 带缓存的空号判断。文件指纹没变就直接复用上次结论。 */
    public static boolean isProbablyEmptyCached(Path file) {
        long stamp;
        try {
            stamp = Files.getLastModifiedTime(file).toMillis() * 31L + Files.size(file);
        } catch (Throwable t) {
            stamp = -1L;
        }
        if (stamp >= 0) {
            EmptyCacheEntry cached = EMPTY_CACHE.get(file);
            if (cached != null && cached.stamp == stamp) {
                return cached.empty;
            }
        }
        boolean result = isProbablyEmpty(readQuiet(file));
        if (stamp >= 0) {
            if (EMPTY_CACHE.size() > EMPTY_CACHE_LIMIT) {
                EMPTY_CACHE.clear();
            }
            EMPTY_CACHE.put(file, new EmptyCacheEntry(stamp, result));
        }
        return result;
    }

    private static Tag readQuiet(Path file) {
        try {
            return NbtIo.readCompressed(file);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean listNotEmpty(Tag parent, String key) {
        Tag t = parent.get(key);
        return t != null && t.isList() && !t.asList().isEmpty();
    }

    private static boolean intGreaterThan(Tag parent, String key, int threshold) {
        Tag t = parent.get(key);
        if (t == null || t.type() != Tag.INT) {
            return false;
        }
        try {
            return (Integer) t.rawValue() > threshold;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean intNotEqual(Tag parent, String key, int expected) {
        Tag t = parent.get(key);
        if (t == null || t.type() != Tag.INT) {
            return false;
        }
        try {
            return (Integer) t.rawValue() != expected;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean floatGreaterThan(Tag parent, String key, float threshold) {
        Tag t = parent.get(key);
        if (t == null || t.type() != Tag.FLOAT) {
            return false;
        }
        try {
            return (Float) t.rawValue() > threshold;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean floatNotEqual(Tag parent, String key, float expected) {
        Tag t = parent.get(key);
        if (t == null || t.type() != Tag.FLOAT) {
            return false;
        }
        try {
            // 浮点直接比相等，这里比的是「有没有被写过」，用极小误差容差
            return Math.abs((Float) t.rawValue() - expected) > 0.001f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean boolIsTrue(Tag parent, String key) {
        Tag t = parent.get(key);
        if (t == null || t.type() != Tag.BYTE) {
            return false;
        }
        try {
            return (Byte) t.rawValue() != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean boolIsFalse(Tag parent, String key) {
        Tag t = parent.get(key);
        if (t == null || t.type() != Tag.BYTE) {
            return false;
        }
        try {
            return (Byte) t.rawValue() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把自己那份玩家数据复制一份 '.spd_backup'，只做一次，保留最原始的那份。 */
    public static void backupOwnPlayerData(Path worldDir, UUID localUuid) {
        backupOnce(worldDir.resolve("playerdata").resolve(localUuid + ".dat"));
        backupOnce(worldDir.resolve("players").resolve("data").resolve(localUuid + ".dat"));
    }

    /**
     * 备份一份玩家数据，<b>只在备份不存在时才写</b>。
     *
     * <p>这是「保留最原始那份」策略的核心：反复调用不会把第一次的原始备份
     * 覆盖成后来已被修改的版本。所有需要备份的地方都必须走这里，
     * 不能直接用 {@code Files.copy(..., REPLACE_EXISTING)}。</p>
     */
    public static void backupOnce(Path playerDataFile) {
        try {
            if (!Files.isRegularFile(playerDataFile)) {
                return;
            }
            Path backup = backupPathOf(playerDataFile);
            if (Files.exists(backup)) {
                return;
            }
            Files.copy(playerDataFile, backup);
            SpdLog.info("已备份玩家数据到 " + backup.getFileName());
        } catch (IOException e) {
            SpdLog.error("备份玩家数据失败：" + playerDataFile, e);
        }
    }

    public static Path backupPathOf(Path playerDataFile) {
        return playerDataFile.resolveSibling(playerDataFile.getFileName() + BACKUP_SUFFIX);
    }

    /** 先写临时文件再原子替换，避免中途崩溃导致 level.dat 损坏。 */
    private static void writeAtomically(Tag root, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            NbtIo.writeCompressed(root, tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
