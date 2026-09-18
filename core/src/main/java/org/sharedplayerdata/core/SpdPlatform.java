package org.sharedplayerdata.core;

/**
 * 核心模块需要的「游戏侧能力」。
 *
 * <h3>为什么必须由桥接层实现（重要）</h3>
 * Forge / NeoForge 在构建时会把 Minecraft 的成员变量名重映射成 SRG 名
 * （{@code level → f_90999_}），Fabric 用 intermediary 名（{@code field_1724}）。
 * 这种重映射只对<b>编译期的符号引用</b>生效，对字符串字面量无效——
 * 所以核心模块里写 {@code mc.getClass().getField("level")} 在开发时能跑，
 * 到了玩家机器上必然 {@code NoSuchFieldException}。
 *
 * <p>因此凡是涉及 Minecraft 成员的操作，一律由桥接层用编译期代码提供；
 * 桥接层里的 {@code mc.level} 会被正确重映射，运行时才真的有效。</p>
 *
 * <p>核心模块只保留纯文件操作（扫描 / 修正），那些不需要碰 MC 类。</p>
 */
public interface SpdPlatform {

    Object minecraft();

    /** 当前是否在世界中（在主菜单时返回 false）。 */
    boolean isInWorld();

    /** 把一行文字发到聊天栏。 */
    void sendMessage(String text);

    /** 由桥接层在客户端就绪后调用一次。 */
    static void set(SpdPlatform impl) {
        Holder.INSTANCE = impl;
    }

    static SpdPlatform get() {
        return Holder.INSTANCE;
    }

    static boolean available() {
        return Holder.INSTANCE != null;
    }

    /** 持有单例（接口不能有实例字段，借嵌套类实现）。 */
    final class Holder {
        static volatile SpdPlatform INSTANCE;

        private Holder() {
        }
    }
}
