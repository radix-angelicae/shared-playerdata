package org.sharedplayerdata.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * 把 {@code /spd} 挂到各加载器的客户端命令注册点上。
 *
 * <p>三个加载器的注册方式完全不同，而且 Fabric 的还依赖可选的 Fabric API，
 * 所以这里全部用反射：能挂上就挂，挂不上就只是没有命令，<b>核心修正逻辑照常工作</b>。</p>
 *
 * <ul>
 *   <li>NeoForge：{@code NeoForge.EVENT_BUS} + {@code RegisterClientCommandsEvent}</li>
 *   <li>Forge：{@code MinecraftForge.EVENT_BUS} + {@code RegisterClientCommandsEvent}</li>
 *   <li>Fabric：{@code ClientCommandRegistrationCallback.EVENT}（需要装了 Fabric API）</li>
 * </ul>
 */
public final class CommandHook {

    private static volatile boolean attempted;
    private static volatile boolean registered;

    private CommandHook() {
    }

    public static boolean isRegistered() {
        return registered;
    }

    /** 只尝试一次；可在客户端就绪后反复调用，不会重复注册。 */
    public static synchronized void tryRegister() {
        if (attempted) {
            return;
        }
        attempted = true;
        if (tryNeoForge()) {
            return;
        }
        if (tryForge()) {
            return;
        }
        if (tryFabric()) {
            return;
        }
        SpdLog.info("没有找到可用的客户端命令注册点，/spd 命令不可用（存档修正仍然正常）。"
                + "Fabric 用户请确认已安装 Fabric API。");
    }

    // ------------------------------------------------------------ NeoForge / Forge

    private static boolean tryNeoForge() {
        return tryEventBus("net.neoforged.neoforge.common.NeoForge",
                "net.neoforged.neoforge.client.event.RegisterClientCommandsEvent");
    }

    private static boolean tryForge() {
        return tryEventBus("net.minecraftforge.common.MinecraftForge",
                "net.minecraftforge.client.event.RegisterClientCommandsEvent");
    }

    private static boolean tryEventBus(String busHolderName, String eventClassName) {
        try {
            Class<?> busHolder = Class.forName(busHolderName);
            Class<?> eventCls = Class.forName(eventClassName);
            Object bus = null;
            for (java.lang.reflect.Field f : busHolder.getFields()) {
                if ("EVENT_BUS".equals(f.getName())) {
                    bus = f.get(null);
                    break;
                }
            }
            if (bus == null) {
                return false;
            }

            Object listener = makeConsumer(eventCls);
            if (listener == null) {
                return false;
            }

            // 优先用带显式事件类型的重载：动态代理没有泛型信息，
            // 靠 Consumer<T> 推断事件类型的那个重载会失败。
            Method adder = findAddListener(bus.getClass());
            if (adder == null) {
                return false;
            }
            Object[] args = buildAddListenerArgs(adder, eventCls, listener);
            adder.invoke(bus, args);
            registered = true;
            SpdLog.info("已挂上客户端命令注册点：" + eventClassName);
            return true;
        } catch (Throwable t) {
            SpdLog.warn("挂载 " + eventClassName + " 失败：" + t);
            return false;
        }
    }

    private static Method findAddListener(Class<?> busClass) {
        Method best = null;
        for (Method m : busClass.getMethods()) {
            if (!"addListener".equals(m.getName())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 4 && ps[2] == Class.class && ps[3] == Consumer.class) {
                return m; // (EventPriority, boolean, Class<T>, Consumer<T>)
            }
            if (ps.length == 2 && ps[0] == Class.class && ps[1] == Consumer.class) {
                if (best == null || best.getParameterTypes().length == 4) {
                    best = m; // (Class<T>, Consumer<T>)
                }
            }
        }
        return best;
    }

    private static Object[] buildAddListenerArgs(Method adder, Class<?> eventCls, Object listener) {
        if (adder.getParameterTypes().length == 2) {
            return new Object[]{eventCls, listener};
        }
        // (EventPriority, boolean, Class, Consumer)
        Object priority = null;
        try {
            Class<?> prioCls = Class.forName("net.minecraftforge.eventbus.api.EventPriority");
            if (!prioCls.isEnum()) {
                prioCls = Class.forName("net.neoforged.bus.api.EventPriority");
            }
            for (Object c : prioCls.getEnumConstants()) {
                if ("NORMAL".equals(((Enum<?>) c).name())) {
                    priority = c;
                    break;
                }
            }
            if (priority == null) {
                priority = prioCls.getEnumConstants()[0];
            }
        } catch (Throwable ignored) {
            // 留 null，让调用失败后走降级
        }
        return new Object[]{priority, Boolean.FALSE, eventCls, listener};
    }

    /** 造一个接受事件对象、取出 dispatcher 后交给 CommandBridge 的 Consumer。 */
    private static Object makeConsumer(final Class<?> eventCls) {
        try {
            return Proxy.newProxyInstance(CommandHook.class.getClassLoader(),
                    new Class<?>[]{Consumer.class}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("accept".equals(method.getName()) && args != null && args.length == 1) {
                                onCommandEvent(args[0], eventCls);
                            }
                            return null;
                        }
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    private static void onCommandEvent(Object event, Class<?> eventCls) {
        try {
            Method getDispatcher = eventCls.getMethod("getDispatcher");
            Object dispatcher = getDispatcher.invoke(event);
            CommandBridge.register(dispatcher);
        } catch (Throwable t) {
            SpdLog.warn("处理客户端命令注册事件失败：" + t);
        }
    }

    // ------------------------------------------------------------ Fabric

    private static boolean tryFabric() {
        for (String clsName : new String[]{
                "net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback",
                "net.fabricmc.fabric.api.client.command.v1.ClientCommandRegistrationCallback"}) {
            try {
                Class<?> cbCls = Class.forName(clsName);
                Object event = null;
                for (java.lang.reflect.Field f : cbCls.getFields()) {
                    if ("EVENT".equals(f.getName())) {
                        event = f.get(null);
                        break;
                    }
                }
                if (event == null) {
                    continue;
                }
                Method register = null;
                for (Method m : event.getClass().getMethods()) {
                    if ("register".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        register = m;
                        break;
                    }
                }
                if (register == null) {
                    continue;
                }
                Class<?> cbInterface = register.getParameterTypes()[0];
                Object impl = Proxy.newProxyInstance(CommandHook.class.getClassLoader(),
                        new Class<?>[]{cbInterface}, new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) {
                                if (args != null && args.length >= 1) {
                                    CommandBridge.register(args[0]);
                                }
                                return null;
                            }
                        });
                register.invoke(event, impl);
                registered = true;
                SpdLog.info("已挂上 Fabric 客户端命令注册点：" + clsName);
                return true;
            } catch (Throwable t) {
                SpdLog.warn("挂载 " + clsName + " 失败：" + t);
            }
        }
        return false;
    }
}
