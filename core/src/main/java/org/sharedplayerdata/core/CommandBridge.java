package org.sharedplayerdata.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * 游戏内命令 {@code /spd} 的注册与实现。
 *
 * <p>核心模块不引用 Brigadier / Minecraft 的任何类，全部通过反射完成，因此：</p>
 * <ul>
 *   <li>一份代码通吃 Fabric / Forge / NeoForge；</li>
 *   <li>命令 API 缺失（比如 Fabric 没装 Fabric API）时优雅降级，不影响核心功能。</li>
 * </ul>
 *
 * <p>子命令：{@code status / list / help}</p>
 *
 * <h3>为什么只有只读命令</h3>
 * 整个模组的工作方式是：在主菜单（不在世界中）自动扫描并修正所有存档的
 * {@code level.dat}，之后游戏按 UUID 各读各的数据，不需要玩家做任何事。
 *
 * <p>所以命令层只保留<b>只读</b>的诊断命令，随时可用、也只在世界中用得到——
 * 进世界发现不对时，用它看清「到底是谁的数据」。</p>
 *
 * <p>写盘类命令（scan / adopt / reload）已移除：
 * 在主菜单自动扫描已经覆盖了这个需求，而开放它们反而带来
 * 与游戏抢文件、写坏存档的风险。</p>
 */
public final class CommandBridge {

    private CommandBridge() {
    }

    /**
     * 把 {@code /spd} 注册进给定的 Brigadier dispatcher。
     * 返回是否注册成功；失败时命令不可用，但核心修正逻辑不受影响。
     */
    public static boolean register(Object dispatcherObj) {
        if (dispatcherObj == null) {
            return false;
        }
        try {
            Class<?> literalCls = Class.forName("com.mojang.brigadier.builder.LiteralArgumentBuilder");
            Class<?> argCls = Class.forName("com.mojang.brigadier.builder.ArgumentBuilder");
            Class<?> commandCls = Class.forName("com.mojang.brigadier.Command");

            Method literal = literalCls.getMethod("literal", String.class);
            Method then = argCls.getMethod("then", argCls);
            Method executes = argCls.getMethod("executes", commandCls);

            Object root = literal.invoke(null, "spd");

            // 全是纯字面量子命令，不需要参数解析
            for (String name : new String[]{"status", "list", "help"}) {
                Object node = literal.invoke(null, name);
                executes.invoke(node, commandProxy(commandCls, name));
                then.invoke(root, node);
            }

            // 不带子命令时给个提示
            executes.invoke(root, commandProxy(commandCls, "help"));

            Method register = dispatcherObj.getClass().getMethod("register", literalCls);
            register.invoke(dispatcherObj, root);
            SpdLog.info("已注册游戏内命令 /spd");
            return true;
        } catch (Throwable t) {
            SpdLog.warn("注册 /spd 命令失败（不影响存档修正）：" + t);
            return false;
        }
    }

    /** 造一个 {@code Command} 的动态代理，避免核心模块依赖 Brigadier 类型。 */
    private static Object commandProxy(Class<?> commandCls, final String action) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("run".equals(name) && args != null && args.length >= 1) {
                    return Integer.valueOf(run(action, args[0]));
                }
                if ("test".equals(name)) {
                    return Boolean.TRUE;
                }
                Class<?> ret = method.getReturnType();
                if (ret == int.class) {
                    return Integer.valueOf(0);
                }
                if (ret == boolean.class) {
                    return Boolean.FALSE;
                }
                if (ret == long.class) {
                    return Long.valueOf(0L);
                }
                return null;
            }
        };
        return Proxy.newProxyInstance(commandCls.getClassLoader(), new Class<?>[]{commandCls}, handler);
    }

    /** 实际执行命令，返回 Brigadier 的结果码。 */
    private static int run(String action, Object context) {
        List<String> lines;
        try {
            if ("status".equals(action)) {
                lines = SpdRuntime.status();
            } else if ("list".equals(action)) {
                lines = SpdRuntime.listData();
            } else {
                lines = help();
            }
        } catch (Throwable t) {
            SpdLog.error("执行命令 /spd " + action + " 出错", t);
            lines = new java.util.ArrayList<String>();
            lines.add("命令执行出错：" + t);
        }
        for (String line : lines) {
            SpdLog.info("[cmd] " + line);
        }
        sendToChat(lines);
        return 1;
    }

    private static List<String> help() {
        List<String> out = new java.util.ArrayList<String>();
        out.add("== Shared Player Data ==");
        out.add("/spd status   查看你的 UUID、各存档的数据归属");
        out.add("/spd list     列出各存档里现存的玩家数据文件");
        out.add("");
        out.add("存档的修正在主菜单自动完成，不需要手动操作。");
        return out;
    }

    // ------------------------------------------------------------------ 回显

    /** 尽力把结果发到聊天栏；失败就只留日志。走 SpdPlatform（桥接层编译期实现）。 */
    private static void sendToChat(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        SpdPlatform p = SpdPlatform.get();
        if (p == null) {
            return;
        }
        int limit = Math.min(lines.size(), 12);
        for (int i = 0; i < limit; i++) {
            try {
                p.sendMessage(lines.get(i));
            } catch (Throwable ignored) {
                // 单行失败不影响其他行
            }
        }
        if (lines.size() > limit) {
            try {
                p.sendMessage("…其余 " + (lines.size() - limit) + " 行见日志");
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }
}
