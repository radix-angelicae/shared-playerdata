package org.sharedplayerdata.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 命令行入口：不装模组也能修存档。
 *
 * <pre>
 *   java -jar sharedplayerdata-core.jar &lt;.minecraft 或 saves 目录&gt; &lt;你的UUID&gt;
 *   java -jar sharedplayerdata-core.jar ~/.minecraft 12345678-1234-1234-1234-123456789abc
 * </pre>
 *
 * <p>UUID 可以在游戏内 / 启动器 / NameMC 查到；离线模式玩家的 UUID 由
 * {@code MD5("OfflinePlayer:" + 游戏名)} 生成，可用 MC 的离线 UUID 计算器得到。</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("用法：java -jar sharedplayerdata-core.jar <.minecraft目录|saves目录> <你的UUID>");
            System.out.println("示例：java -jar sharedplayerdata-core.jar \"/Users/me/Library/Application Support/minecraft\" 069a79f4-44e9-4726-a5be-fca90e38aaf5");
            return;
        }
        Path dir = Paths.get(args[0]).toAbsolutePath().normalize();
        UUID uuid;
        try {
            uuid = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            System.out.println("UUID 格式不正确：" + args[1]);
            return;
        }
        SharedPlayerData.run(dir, uuid);
    }
}
