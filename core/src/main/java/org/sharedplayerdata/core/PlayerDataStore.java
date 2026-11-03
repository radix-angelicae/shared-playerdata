package org.sharedplayerdata.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 玩家数据文件（{@code playerdata/<UUID>.dat}）的读写与清点。
 *
 * <p>Minecraft 在不同版本把玩家数据放在两个位置：</p>
 * <ul>
 *   <li>1.21.11 及更早：{@code <世界>/playerdata/<UUID>.dat}</li>
 *   <li>26.1 起：{@code <世界>/players/data/<UUID>.dat}</li>
 * </ul>
 * 本类同时处理两者，取“有数据的那一个”。
 */
public final class PlayerDataStore {

    private static final String LEGACY_DIR = "playerdata";
    private static final String MODERN_PARENT = "players";
    private static final String MODERN_CHILD = "data";

    private PlayerDataStore() {
    }

    /** 一个玩家数据文件的快照。 */
    public static final class Entry {
        /** 文件名符合 UUID 格式时为对应 UUID；孤儿归档（{@code spd-orphan.dat}）为 null。 */
        public final UUID uuid;
        public final Path file;
        public final long size;
        public final long modifiedMillis;
        public final boolean orphan;

        Entry(UUID uuid, Path file, long size, long modifiedMillis, boolean orphan) {
            this.uuid = uuid;
            this.file = file;
            this.size = size;
            this.modifiedMillis = modifiedMillis;
            this.orphan = orphan;
        }

        public boolean isEmpty() {
            return size <= 0L;
        }
    }

    /** 该世界使用的玩家数据目录（取存在的那个；都没有时返回旧版路径）。 */
    public static Path dirOf(Path worldDir) {
        Path modern = worldDir.resolve(MODERN_PARENT).resolve(MODERN_CHILD);
        if (Files.isDirectory(modern)) {
            return modern;
        }
        return worldDir.resolve(LEGACY_DIR);
    }

    public static Path fileOf(Path worldDir, UUID uuid) {
        return dirOf(worldDir).resolve(uuid + ".dat");
    }

    /** 当前玩家在这个世界里有没有自己的数据文件。 */
    public static boolean has(Path worldDir, UUID uuid) {
        return Files.isRegularFile(worldDir.resolve(LEGACY_DIR).resolve(uuid + ".dat"))
                || Files.isRegularFile(worldDir.resolve(MODERN_PARENT).resolve(MODERN_CHILD)
                        .resolve(uuid + ".dat"));
    }

    /**
     * 列出这个世界里所有玩家数据文件，按最后修改时间倒序（最近玩过的排前面）。
     * 目录不存在或读取失败时返回空列表——绝不上抛。
     */
    public static List<Entry> list(Path worldDir) {
        List<Entry> out = new ArrayList<>();
        collect(worldDir.resolve(LEGACY_DIR), out);
        collect(worldDir.resolve(MODERN_PARENT).resolve(MODERN_CHILD), out);
        Collections.sort(out, (a, b) -> Long.compare(b.modifiedMillis, a.modifiedMillis));
        return out;
    }

    private static void collect(Path dir, List<Entry> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.dat")) {
            for (Path child : stream) {
                if (!Files.isRegularFile(child)) {
                    continue;
                }
                String fileName = child.getFileName().toString();
                UUID uuid = uuidFromFileName(fileName);
                // 孤儿归档：内嵌数据没记录 UUID 时的兜底文件。必须也列出来，
                // 否则这份数据永远没法被认领，等于白归档。
                boolean orphan = uuid == null && LevelDatFixer.ORPHAN_FILE.equals(fileName);
                if (uuid == null && !orphan) {
                    continue;
                }
                long size;
                long mtime;
                try {
                    size = Files.size(child);
                    mtime = Files.getLastModifiedTime(child).toMillis();
                } catch (IOException e) {
                    size = -1L;
                    mtime = 0L;
                }
                out.add(new Entry(uuid, child, size, mtime, orphan));
            }
        } catch (IOException e) {
            SpdLog.warn("读取玩家数据目录失败：" + dir + "（" + e + "）");
        }
    }

    /** 从 "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.dat" 解析 UUID，失败返回 null。 */
    public static UUID uuidFromFileName(String fileName) {
        String name = fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        try {
            return UUID.fromString(name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
