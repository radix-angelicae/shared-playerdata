package org.sharedplayerdata.core;

/**
 * 核心模块的日志出口。
 *
 * <p>默认直接打印到标准输出（启动器日志里能看到）。Forge / Fabric / NeoForge 的桥接层会
 * 把输出重定向到各自的日志系统，这样核心模块就不用依赖任何加载器 API 或日志框架。</p>
 */
public final class SpdLog {

    public enum Level {
        INFO, WARN, ERROR
    }

    public interface Sink {
        void log(Level level, String message);
    }

    private static volatile Sink sink = new Sink() {
        @Override
        public void log(Level level, String message) {
            System.out.println("[SharedPlayerData/" + level + "] " + message);
        }
    };

    private SpdLog() {
    }

    public static void setSink(Sink newSink) {
        if (newSink != null) {
            sink = newSink;
        }
    }

    public static void info(String message) {
        sink.log(Level.INFO, message);
    }

    public static void warn(String message) {
        sink.log(Level.WARN, message);
    }

    public static void error(String message) {
        sink.log(Level.ERROR, message);
    }

    public static void error(String message, Throwable t) {
        sink.log(Level.ERROR, message + (t == null ? "" : " : " + stackTraceOf(t)));
    }

    /** 把堆栈转成字符串。只写 t.toString() 的话排查时看不到出错位置。 */
    public static String stackTraceOf(Throwable t) {
        if (t == null) {
            return "";
        }
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        } catch (Throwable ignored) {
            return String.valueOf(t);
        }
    }
}
