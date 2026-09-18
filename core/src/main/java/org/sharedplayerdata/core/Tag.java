package org.sharedplayerdata.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个极简、零依赖的 NBT 标签实现。
 *
 * <p>之所以不用 {@code net.minecraft.nbt.CompoundTag}，是为了让「核心逻辑」完全脱离
 * Minecraft 与任何加载器 API：同一份代码可以原样编译进 Forge / Fabric / NeoForge / 纯命令行，
 * 不必关心 Mojang、SRG、intermediary 等映射差异。</p>
 *
 * <p>支持全部 13 种标签类型，读写格式与 Mojang NBT 规范一致（含 modified UTF-8）。</p>
 */
public final class Tag {

    public static final byte END = 0;
    public static final byte BYTE = 1;
    public static final byte SHORT = 2;
    public static final byte INT = 3;
    public static final byte LONG = 4;
    public static final byte FLOAT = 5;
    public static final byte DOUBLE = 6;
    public static final byte BYTE_ARRAY = 7;
    public static final byte STRING = 8;
    public static final byte LIST = 9;
    public static final byte COMPOUND = 10;
    public static final byte INT_ARRAY = 11;
    public static final byte LONG_ARRAY = 12;

    private final byte type;
    private final Object value;

    private Tag(byte type, Object value) {
        this.type = type;
        this.value = value;
    }

    // ---------------------------------------------------------------- 工厂方法

    public static Tag newCompound() {
        return new Tag(COMPOUND, new LinkedHashMap<String, Tag>());
    }

    public static Tag newList() {
        return new Tag(LIST, new ArrayList<Tag>());
    }

    public static Tag ofByte(byte v) {
        return new Tag(BYTE, v);
    }

    public static Tag ofShort(short v) {
        return new Tag(SHORT, v);
    }

    public static Tag ofInt(int v) {
        return new Tag(INT, v);
    }

    public static Tag ofLong(long v) {
        return new Tag(LONG, v);
    }

    public static Tag ofFloat(float v) {
        return new Tag(FLOAT, v);
    }

    public static Tag ofDouble(double v) {
        return new Tag(DOUBLE, v);
    }

    public static Tag ofString(String v) {
        return new Tag(STRING, v == null ? "" : v);
    }

    public static Tag ofByteArray(byte[] v) {
        return new Tag(BYTE_ARRAY, v);
    }

    public static Tag ofIntArray(int[] v) {
        return new Tag(INT_ARRAY, v);
    }

    public static Tag ofLongArray(long[] v) {
        return new Tag(LONG_ARRAY, v);
    }

    /** 把 UUID 转成 NBT 的 int[4] 形式（与 Minecraft 玩家数据里的 {@code UUID} 标签一致）。 */
    public static Tag ofUuid(UUID uuid) {
        return ofIntArray(Uuids.toIntArray(uuid));
    }

    // ---------------------------------------------------------------- 读取

    public byte type() {
        return type;
    }

    public boolean isCompound() {
        return type == COMPOUND;
    }

    public boolean isList() {
        return type == LIST;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Tag> asMap() {
        if (type != COMPOUND) {
            throw new IllegalStateException("not a compound tag (type=" + type + ")");
        }
        return (Map<String, Tag>) value;
    }

    @SuppressWarnings("unchecked")
    public List<Tag> asList() {
        if (type != LIST) {
            throw new IllegalStateException("not a list tag (type=" + type + ")");
        }
        return (List<Tag>) value;
    }

    public boolean contains(String key) {
        return type == COMPOUND && asMap().containsKey(key);
    }

    public Tag get(String key) {
        return type == COMPOUND ? asMap().get(key) : null;
    }

    public void put(String key, Tag tag) {
        asMap().put(key, tag);
    }

    public void remove(String key) {
        if (type == COMPOUND) {
            asMap().remove(key);
        }
    }

    public Tag getCompound(String key) {
        Tag t = get(key);
        return t != null && t.isCompound() ? t : null;
    }

    public List<Tag> getList(String key) {
        Tag t = get(key);
        return t != null && t.isList() ? t.asList() : null;
    }

    public int[] getIntArray(String key) {
        Tag t = get(key);
        return t != null && t.type == INT_ARRAY ? (int[]) t.value : null;
    }

    public String getString(String key, String fallback) {
        Tag t = get(key);
        return t != null && t.type == STRING ? (String) t.value : fallback;
    }

    public int getInt(String key, int fallback) {
        Tag t = get(key);
        return t != null && t.type == INT ? (Integer) t.value : fallback;
    }

    /** 原始值，仅由序列化器使用。 */
    public Object rawValue() {
        return value;
    }

    /** 列表元素类型；空列表返回 {@link #END}，与 Minecraft 写入空列表时的行为一致。 */
    public byte elementType() {
        if (type != LIST) {
            return END;
        }
        List<Tag> l = asList();
        return l.isEmpty() ? END : l.get(0).type();
    }

    @Override
    public String toString() {
        return "Tag[type=" + type + ", value=" + value + "]";
    }
}
