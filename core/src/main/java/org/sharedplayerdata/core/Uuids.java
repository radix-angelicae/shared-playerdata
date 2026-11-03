package org.sharedplayerdata.core;

import java.util.UUID;

/** UUID 与 NBT int[4] 之间的转换工具。 */
public final class Uuids {

    private Uuids() {
    }

    /** Minecraft 玩家数据里的 UUID 以 int[4] 保存，高 64 位在前。 */
    public static int[] toIntArray(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        return new int[]{(int) (msb >> 32), (int) msb, (int) (lsb >> 32), (int) lsb};
    }

    /** 长度不是 4 时返回 null（数据缺失或格式异常）。 */
    public static UUID fromIntArray(int[] a) {
        if (a == null || a.length != 4) {
            return null;
        }
        long msb = ((long) a[0] << 32) | (a[1] & 0xFFFFFFFFL);
        long lsb = ((long) a[2] << 32) | (a[3] & 0xFFFFFFFFL);
        return new UUID(msb, lsb);
    }

    /**
     * 全零 UUID（{@code 00000000-0000-0000-0000-000000000000}）。
     *
     * <p>这不是一个合法的玩家身份，通常是「登录信息还没准备好」。拿它去比对，
     * 任何存档里的内嵌数据都会被判定成「别人的」而删掉，结果就是所有人进存档
     * 都变空号。凡是遇到它都应当拒绝操作。</p>
     */
    public static boolean isNil(UUID uuid) {
        return uuid == null
                || (uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L);
    }
}
