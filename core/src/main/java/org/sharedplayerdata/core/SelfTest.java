package org.sharedplayerdata.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 零依赖自测：不启动 Minecraft 也能验证核心逻辑。
 *
 * <pre>
 *   javac -d out org/sharedplayerdata/core/*.java
 *   java -cp out org.sharedplayerdata.core.SelfTest
 * </pre>
 */
public final class SelfTest {

    private static int failures = 0;

    private SelfTest() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("spd-selftest");
        Path saves = root.resolve("saves");

        UUID me = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID host = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        // 场景表（括号内是本次扫描的预期结果）
        //   1) 别人当房主、自己没有数据  → 归档 + 移除  [FIXED]
        //   2) 自己的存档               → 原样不动    [CLEAN]（但会备份自己的数据）
        //   3) 没有内嵌 Player 标签      → 无事可做    [CLEAN]
        //   4) 26.1 格式，数据已落地     → 移除指针    [FIXED]
        //   5) 26.1 格式，数据没落地     → 保留指针    [CLEAN]（删了会丢数据）
        Path foreign = createWorld(saves, "Foreign World", host, true, false);
        Path mine = createWorld(saves, "My World", me, true, true);
        Path noPlayer = createWorld(saves, "No Player Tag", host, false, false);
        Path modern = createModernWorld(saves, "Modern World", host, true);
        Path modernNoData = createModernWorld(saves, "Modern NoData", host, false);

        int fixed = countFixed(saves, me);
        check("修正数量应为 2（场景 1 和 4）", fixed == 2, "实际 " + fixed);

        check("他人存档的 Player 已移除", !hasPlayer(foreign), "仍存在");
        check("他人存档的 LevelName 保留", "Foreign World".equals(levelName(foreign)), levelName(foreign));
        check("他人存档的其它数据保留", data(foreign).getInt("DataVersion", -1) == 3337, "DataVersion 丢失");

        check("自己的存档未被改动", hasPlayer(mine), "自己的数据被误删了");
        check("自己的 playerdata 已备份",
                Files.isRegularFile(mine.resolve("playerdata").resolve(me + ".dat" + LevelDatFixer.BACKUP_SUFFIX)),
                "没有生成备份");

        check("没有内嵌 Player 时不会凭空添加", !hasPlayer(noPlayer), "多出了 Player 标签");
        check("房主的数据被归档保存，没有丢",
                Files.isRegularFile(foreign.resolve("playerdata").resolve(host + ".dat")),
                "缺少 playerdata/" + host + ".dat");
        check("归档的数据能被解析",
                NbtIo.readCompressed(foreign.resolve("playerdata").resolve(host + ".dat")) != null,
                "归档文件损坏");

        check("26.1 格式且数据已落地 → 移除指针",
                !data(modern).contains("singleplayer_uuid"), "指针仍在");
        check("26.1 格式但数据没落地 → 保留指针（避免数据失联）",
                data(modernNoData).contains("singleplayer_uuid"),
                "指针被删了但数据没落地，会丢数据");
        check("26.1 格式的 Player 标签未被误加", !data(modern).contains("Player"), "不该有 Player");

        // 幂等性：再跑一次不应再改动任何东西
        // （这一项同时能抓到「缓存把 FIXED 结果复用了」的 bug——
        //  那样每次扫描都会重复报「修正了 N 个」）
        int second = countFixed(saves, me);
        check("重复扫描幂等", second == 0, "第二次修正了 " + second + " 个");
        int third = countFixed(saves, me);
        check("第三次扫描仍幂等（缓存不会重复计数）", third == 0, "第三次修正了 " + third + " 个");

        // 空号判断：玩过的玩家不能被误判成空号。
        // 基准必须是「结构完整」的空号数据——否则触发的是完整性检查而不是
        // 各项数值检查，测的就不是想测的东西了。
        Tag played = emptyPlayer(me);
        check("完整空号确实被判为空号", LevelDatFixer.isProbablyEmpty(played), "基准有误");
        played.put("Health", Tag.ofFloat(14.5f));   // 受过伤
        check("受伤的玩家不算空号", !LevelDatFixer.isProbablyEmpty(played), "被误判为空号");
        played.put("Health", Tag.ofFloat(20.0f));
        played.put("foodLevel", Tag.ofInt(17));     // 饿过
        check("吃过东西的玩家不算空号", !LevelDatFixer.isProbablyEmpty(played), "被误判为空号");
        played.put("foodLevel", Tag.ofInt(20));
        played.put("XpTotal", Tag.ofInt(120));      // 有累计经验
        check("有累计经验的玩家不算空号", !LevelDatFixer.isProbablyEmpty(played), "被误判为空号");
        played.put("XpTotal", Tag.ofInt(0));
        played.put("SelectedItemSlot", Tag.ofInt(3)); // 换过快捷栏
        check("换过快捷栏的玩家不算空号", !LevelDatFixer.isProbablyEmpty(played), "被误判为空号");

        // 残缺数据绝不能被当成空号覆盖掉
        check("残缺数据不算空号",
                !LevelDatFixer.isProbablyEmpty(truncatedPlayer(me)),
                "残缺数据被误判成空号，会被静默覆盖");
        check("只有 UUID 的残缺数据不算空号",
                !LevelDatFixer.isProbablyEmpty(truncatedPlayer(host)),
                "被误判成空号");
        // 完整且各项为默认值 → 才是真空号
        check("完整且全为默认值 → 是空号",
                LevelDatFixer.isProbablyEmpty(emptyPlayer(me)), "该判为空号却没判成");

        SpdRuntime.init(root, me);

        // 备份策略：备份一旦生成就不能被后续操作覆盖成「后来被改过」的版本。
        // 这条规则是防丢数据的最后一道防线，必须钉死。
        Path backupTarget = mine.resolve("playerdata").resolve(me + ".dat");
        Path backupFile = LevelDatFixer.backupPathOf(backupTarget);
        check("扫描后自己那份已备份", Files.isRegularFile(backupFile), "没有备份");
        long firstBackupStamp = Files.getLastModifiedTime(backupFile).toMillis();
        LevelDatFixer.backupOnce(backupTarget);
        LevelDatFixer.backupOnce(backupTarget);
        check("重复备份不会覆盖原始备份",
                Files.getLastModifiedTime(backupFile).toMillis() == firstBackupStamp,
                "原始备份被覆盖了");

        // 写回的文件必须能被重新解析
        check("写回的 level.dat 可再次解析", NbtIo.readCompressed(foreign.resolve("level.dat")) != null, "解析失败");

        // 全零 UUID 必须被拒绝（否则会把所有人的数据都当成「别人的」删掉）
        Path zeroWorld = createWorld(saves, "Zero World", host, true, false);
        int before = countFixed(saves, UUID.fromString("00000000-0000-0000-0000-000000000000"));
        check("全零 UUID 不会改动任何存档", before == 0, "却修正了 " + before + " 个");
        check("全零 UUID 时内嵌数据仍在", hasPlayer(zeroWorld), "被删掉了");

        // 空号的 playerdata 应该被内嵌数据覆盖（而不是挡住归档）
        Path emptySlot = createWorld(saves, "Empty Slot", host, true, false);
        Path slotFile = emptySlot.resolve("playerdata").resolve(host + ".dat");
        Files.createDirectories(slotFile.getParent());
        NbtIo.writeCompressed(emptyPlayer(host), slotFile);
        check("覆盖前是空号", LevelDatFixer.isProbablyEmpty(NbtIo.readCompressed(slotFile)), "判断有误");
        countFixed(saves, me);
        check("空号数据被内嵌数据覆盖",
                !LevelDatFixer.isProbablyEmpty(NbtIo.readCompressed(slotFile)), "仍是空号");

        deleteRecursively(root);

        if (failures == 0) {
            System.out.println("所有自测通过 ✅");
        } else {
            System.out.println(failures + " 项自测失败 ❌");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 辅助方法

    private static Path createWorld(Path saves, String name, UUID owner, boolean withPlayerTag, boolean withOwnData)
            throws IOException {
        Path world = saves.resolve(name.replace(' ', '_'));
        Files.createDirectories(world.resolve("playerdata"));

        Tag data = Tag.newCompound();
        data.put("LevelName", Tag.ofString(name));
        data.put("DataVersion", Tag.ofInt(3337));
        data.put("SpawnX", Tag.ofInt(100));
        if (withPlayerTag) {
            Tag player = Tag.newCompound();
            player.put("UUID", Tag.ofUuid(owner));
            player.put("Pos", Tag.newList());
            player.getList("Pos").add(Tag.ofDouble(1.5D));
            player.getList("Pos").add(Tag.ofDouble(64.0D));
            player.getList("Pos").add(Tag.ofDouble(-3.25D));
            player.put("Inventory", Tag.newList());
            data.put("Player", player);
        }
        Tag root = Tag.newCompound();
        root.put("Data", data);
        NbtIo.writeCompressed(root, world.resolve("level.dat"));

        if (withOwnData) {
            Tag own = Tag.newCompound();
            own.put("UUID", Tag.ofUuid(owner));
            NbtIo.writeCompressed(own, world.resolve("playerdata").resolve(owner + ".dat"));
        }
        return world;
    }

    private static Path createModernWorld(Path saves, String name, UUID owner, boolean withData)
            throws IOException {
        Path world = saves.resolve(name.replace(' ', '_'));
        Path dataDir = world.resolve("players").resolve("data");
        Files.createDirectories(dataDir);
        Tag data = Tag.newCompound();
        data.put("LevelName", Tag.ofString(name));
        data.put("DataVersion", Tag.ofInt(4774));
        data.put("singleplayer_uuid", Tag.ofUuid(owner));
        Tag root = Tag.newCompound();
        root.put("Data", data);
        NbtIo.writeCompressed(root, world.resolve("level.dat"));
        if (withData) {
            Tag own = Tag.newCompound();
            own.put("UUID", Tag.ofUuid(owner));
            own.put("Inventory", Tag.newList());
            NbtIo.writeCompressed(own, dataDir.resolve(owner + ".dat"));
        }
        return world;
    }

    /** 统计被修正的存档数量（新的 scan 返回报告列表）。 */
    private static int countFixed(Path saves, UUID me) {
        int n = 0;
        for (LevelDatFixer.WorldReport r : LevelDatFixer.scan(saves, me)) {
            if (r.result == LevelDatFixer.Result.FIXED) {
                n++;
            }
        }
        return n;
    }

    /** 一份「真正的新玩家」数据：字段齐全，但各项都是默认值。 */
    private static Tag emptyPlayer(UUID uuid) {
        Tag t = Tag.newCompound();
        t.put("UUID", Tag.ofUuid(uuid));
        t.put("Inventory", Tag.newList());
        t.put("EnderItems", Tag.newList());
        t.put("XpLevel", Tag.ofInt(0));
        // 结构完整性字段（Minecraft 给新玩家必定会写的）
        t.put("Pos", Tag.newList());
        t.put("Rotation", Tag.newList());
        t.put("Health", Tag.ofFloat(20.0f));
        return t;
    }

    /**
     * 一份「残缺」的数据：只有 UUID，关键字段全缺。
     * 这必须被判成非空号——我们看不懂它，就不能覆盖它。
     */
    private static Tag truncatedPlayer(UUID uuid) {
        Tag t = Tag.newCompound();
        t.put("UUID", Tag.ofUuid(uuid));
        return t;
    }

    private static Tag data(Path world) throws IOException {
        return NbtIo.readCompressed(world.resolve("level.dat")).getCompound("Data");
    }

    private static boolean hasPlayer(Path world) throws IOException {
        return data(world).contains("Player");
    }

    private static String levelName(Path world) throws IOException {
        return data(world).getString("LevelName", "<none>");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            System.out.println("  ✓ " + what);
        } else {
            failures++;
            System.out.println("  ✗ " + what + " —— " + detail);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录，删不掉也无所谓
                }
            });
        }
    }
}
