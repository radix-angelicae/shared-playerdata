package org.sharedplayerdata.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 对外门面：三个加载器的桥接层只需要调用这里。 */
public final class SharedPlayerData {

    public static final String MOD_ID = "sharedplayerdata";
    public static final String MOD_NAME = "Shared Player Data";

    private SharedPlayerData() {
    }

    /** 在后台线程执行扫描，避免拖慢启动。 */
    public static void runAsync(Path gameDir, UUID localUuid) {
        Thread thread = new Thread(() -> run(gameDir, localUuid), MOD_NAME + "-Fixer");
        thread.setDaemon(true);
        thread.start();
    }

    /** 扫描并修正存档目录。任何异常都被吞掉——这个模组绝不能因为出错而让游戏崩溃。 */
    public static void run(Path gameDir, UUID localUuid) {
        try {
            SpdRuntime.init(gameDir, localUuid);
            SpdRuntime.scan();
        } catch (Throwable t) {
            SpdLog.error("扫描存档时发生未预期错误", t);
        }
    }

    /** 既接受 .minecraft 目录，也直接接受 saves 目录。 */
    public static Path resolveSavesDir(Path gameDir) {
        if (gameDir == null) {
            return null;
        }
        Path saves = gameDir.resolve("saves");
        if (Files.isDirectory(saves)) {
            return saves;
        }
        return Files.isDirectory(gameDir) ? gameDir : saves;
    }
}
