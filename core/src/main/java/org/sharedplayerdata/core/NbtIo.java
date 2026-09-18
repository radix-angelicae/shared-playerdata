package org.sharedplayerdata.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 极简 NBT 读写器（只依赖 JDK）。
 *
 * <p>{@code level.dat} 是 GZIP 压缩的 NBT；为了容错，本类会先嗅探魔数，
 * 未压缩的 NBT 也能正常读取（写入时始终按 GZIP 输出，与 Minecraft 一致）。</p>
 */
public final class NbtIo {

    private NbtIo() {
    }

    public static boolean isGzip(byte[] data) {
        return data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B;
    }

    /** 读取一个（可能经过 GZIP 压缩的）NBT 文件，根标签必须是 Compound。 */
    public static Tag readCompressed(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        InputStream in = new ByteArrayInputStream(raw);
        if (isGzip(raw)) {
            in = new GZIPInputStream(in);
        }
        try (DataInputStream din = new DataInputStream(new BufferedInputStream(in))) {
            return read(din);
        }
    }

    public static Tag read(DataInputStream din) throws IOException {
        byte type = din.readByte();
        if (type != Tag.COMPOUND) {
            throw new IOException("NBT root tag must be TAG_Compound, got " + type);
        }
        din.readUTF(); // 根标签名，level.dat 中始终为空字符串
        return readPayload(din, type);
    }

    private static Tag readPayload(DataInputStream din, byte type) throws IOException {
        switch (type) {
            case Tag.BYTE:
                return Tag.ofByte(din.readByte());
            case Tag.SHORT:
                return Tag.ofShort(din.readShort());
            case Tag.INT:
                return Tag.ofInt(din.readInt());
            case Tag.LONG:
                return Tag.ofLong(din.readLong());
            case Tag.FLOAT:
                return Tag.ofFloat(din.readFloat());
            case Tag.DOUBLE:
                return Tag.ofDouble(din.readDouble());
            case Tag.BYTE_ARRAY: {
                byte[] a = new byte[readLength(din)];
                din.readFully(a);
                return Tag.ofByteArray(a);
            }
            case Tag.STRING:
                return Tag.ofString(din.readUTF());
            case Tag.LIST: {
                byte elementType = din.readByte();
                int length = readLength(din);
                Tag list = Tag.newList();
                if (elementType == Tag.END) {
                    return list; // 空列表，Minecraft 写入的类型就是 0
                }
                for (int i = 0; i < length; i++) {
                    list.asList().add(readPayload(din, elementType));
                }
                return list;
            }
            case Tag.COMPOUND: {
                Tag compound = Tag.newCompound();
                while (true) {
                    byte childType = din.readByte();
                    if (childType == Tag.END) {
                        break;
                    }
                    String name = din.readUTF();
                    compound.put(name, readPayload(din, childType));
                }
                return compound;
            }
            case Tag.INT_ARRAY: {
                int[] a = new int[readLength(din)];
                for (int i = 0; i < a.length; i++) {
                    a[i] = din.readInt();
                }
                return Tag.ofIntArray(a);
            }
            case Tag.LONG_ARRAY: {
                long[] a = new long[readLength(din)];
                for (int i = 0; i < a.length; i++) {
                    a[i] = din.readLong();
                }
                return Tag.ofLongArray(a);
            }
            default:
                throw new IOException("Unknown NBT tag type: " + type);
        }
    }

    /**
     * 数组 / 列表长度的合理上限（元素个数）。
     *
     * <p>正常的 level.dat 里，最大的数组也就是几 MB 的区块数据，远达不到这个量级。
     * 设上限是为了防止文件损坏时 {@code new byte[hugeNumber]} 直接把内存吃光
     * （OutOfMemoryError 会让整个游戏崩掉）。</p>
     */
    private static final int MAX_ELEMENTS = 1 << 26; // 约 6700 万

    private static int readLength(DataInputStream din) throws IOException {
        int n = din.readInt();
        if (n < 0) {
            throw new IOException("Negative array/list length: " + n);
        }
        if (n > MAX_ELEMENTS) {
            throw new IOException("Implausible array/list length: " + n
                    + " (file is probably corrupt)");
        }
        return n;
    }

    /** 以 GZIP 压缩写出 NBT（根标签名写空字符串，与 Minecraft 一致）。 */
    public static void writeCompressed(Tag root, Path path) throws IOException {
        try (OutputStream fos = Files.newOutputStream(path);
             GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(fos));
             DataOutputStream dout = new DataOutputStream(gzip)) {
            dout.writeByte(root.type());
            dout.writeUTF("");
            writePayload(dout, root);
        }
    }

    private static void writePayload(DataOutputStream dout, Tag tag) throws IOException {
        switch (tag.type()) {
            case Tag.BYTE:
                dout.writeByte((Byte) tag.rawValue());
                break;
            case Tag.SHORT:
                dout.writeShort((Short) tag.rawValue());
                break;
            case Tag.INT:
                dout.writeInt((Integer) tag.rawValue());
                break;
            case Tag.LONG:
                dout.writeLong((Long) tag.rawValue());
                break;
            case Tag.FLOAT:
                dout.writeFloat((Float) tag.rawValue());
                break;
            case Tag.DOUBLE:
                dout.writeDouble((Double) tag.rawValue());
                break;
            case Tag.BYTE_ARRAY: {
                byte[] a = (byte[]) tag.rawValue();
                dout.writeInt(a.length);
                dout.write(a);
                break;
            }
            case Tag.STRING:
                dout.writeUTF((String) tag.rawValue());
                break;
            case Tag.LIST: {
                List<Tag> list = tag.asList();
                dout.writeByte(tag.elementType());
                dout.writeInt(list.size());
                for (Tag child : list) {
                    writePayload(dout, child);
                }
                break;
            }
            case Tag.COMPOUND: {
                for (Map.Entry<String, Tag> entry : tag.asMap().entrySet()) {
                    dout.writeByte(entry.getValue().type());
                    dout.writeUTF(entry.getKey());
                    writePayload(dout, entry.getValue());
                }
                dout.writeByte(Tag.END);
                break;
            }
            case Tag.INT_ARRAY: {
                int[] a = (int[]) tag.rawValue();
                dout.writeInt(a.length);
                for (int v : a) {
                    dout.writeInt(v);
                }
                break;
            }
            case Tag.LONG_ARRAY: {
                long[] a = (long[]) tag.rawValue();
                dout.writeInt(a.length);
                for (long v : a) {
                    dout.writeLong(v);
                }
                break;
            }
            default:
                throw new IOException("Cannot write tag type: " + tag.type());
        }
    }
}
