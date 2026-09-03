package net.sixik.concurrent_library.utils.nbt;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

public final class FastNbtWriter {

    private static final int DEFAULT_INITIAL_CAPACITY = 64;

    private static final VarHandle SHORT_BE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_BE   = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_BE  = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    protected byte[] buffer;
    protected int position;

    // Stack to track nested list headers: elementType (upper 32 bits) and lengthOffset (lower 32 bits)
    private long[] listStack = new long[8];
    private int[] listCounts = new int[8];
    private int listDepth = 0;

    public FastNbtWriter() {
        this(DEFAULT_INITIAL_CAPACITY);
    }

    public FastNbtWriter(int initialCapacity) {
        this.buffer = new byte[Math.max(initialCapacity, 16)];
        this.position = 0;
    }

    public FastNbtWriter beginRoot() {
        return beginRoot("");
    }

    public FastNbtWriter beginRoot(String name) {
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_COMPOUND, name, 0);
        return this;
    }

    public FastNbtWriter endRoot() {
        writeByte(NbtConstants.TAG_END);
        return this;
    }

    public FastNbtWriter beginCompound(String name) {
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_COMPOUND, name, 0);
        return this;
    }

    public FastNbtWriter beginCompound() {
        writeAnonymousHeader(NbtConstants.TAG_COMPOUND);
        return this;
    }

    public FastNbtWriter endCompound() {
        writeByte(NbtConstants.TAG_END);
        return this;
    }

    public FastNbtWriter beginList(String name, byte elementType) {
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_LIST, name, 5); // 1 byte element type + 4 byte count
        byte[] buf = this.buffer;
        int pos = this.position;
        buf[pos++] = elementType;
        pushList(elementType, pos);
        INT_BE.set(buf, pos, 0);
        this.position = pos + 4;
        return this;
    }

    public FastNbtWriter beginList(byte elementType) {
        writeAnonymousHeader(NbtConstants.TAG_LIST);
        ensureCapacity(this.position + 5);
        byte[] buf = this.buffer;
        int pos = this.position;
        buf[pos++] = elementType;
        pushList(elementType, pos);
        INT_BE.set(buf, pos, 0);
        this.position = pos + 4;
        return this;
    }

    public FastNbtWriter endList() {
        if (listDepth <= 0) {
            throw new IllegalStateException("Not inside a list");
        }
        int count = listCounts[--listDepth];
        int countOffset = (int) listStack[listDepth];
        INT_BE.set(this.buffer, countOffset, count);
        return this;
    }

    public FastNbtWriter putByte(String name, byte value) {
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_BYTE, name, 1);
        this.buffer[this.position++] = value;
        return this;
    }

    public FastNbtWriter putByte(String name, int value) {
        return putByte(name, (byte) value);
    }

    public FastNbtWriter putBoolean(String name, boolean value) {
        return putByte(name, (byte) (value ? 1 : 0));
    }

    public FastNbtWriter putShort(String name, short value) {
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_SHORT, name, 2);
        SHORT_BE.set(this.buffer, this.position, value);
        this.position += 2;
        return this;
    }

    public FastNbtWriter putShort(String name, int value) {
        return putShort(name, (short) value);
    }

    public FastNbtWriter putInt(String name, int value) {
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_INT, name, 4);
        INT_BE.set(this.buffer, this.position, value);
        this.position += 4;
        return this;
    }

    public FastNbtWriter putLong(String name, long value) {
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_LONG, name, 8);
        LONG_BE.set(this.buffer, this.position, value);
        this.position += 8;
        return this;
    }

    public FastNbtWriter putFloat(String name, float value) {
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_FLOAT, name, 4);
        INT_BE.set(this.buffer, this.position, Float.floatToRawIntBits(value));
        this.position += 4;
        return this;
    }

    public FastNbtWriter putDouble(String name, double value) {
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_DOUBLE, name, 8);
        LONG_BE.set(this.buffer, this.position, Double.doubleToRawLongBits(value));
        this.position += 8;
        return this;
    }

    public FastNbtWriter putString(String name, String value) {
        int valLen = value.length();
        boolean valAscii = isModifiedUtfAscii(value, valLen);
        int valUtfLen = valAscii ? valLen : modifiedUtfLength(value, valLen);

        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_STRING, name, 2 + valUtfLen);

        byte[] buf = this.buffer;
        int pos = this.position;
        SHORT_BE.set(buf, pos, (short) valUtfLen);
        pos += 2;

        if (valAscii) {
            writeAsciiChars(buf, pos, value, valLen);
        } else {
            writeRawUtfChars(buf, pos, value, valLen);
        }
        this.position = pos + valUtfLen;
        return this;
    }

    public FastNbtWriter putByteArray(String name, byte[] value) {
        int len = value.length;
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_BYTE_ARRAY, name, 4 + len);
        byte[] buf = this.buffer;
        int pos = this.position;
        INT_BE.set(buf, pos, len);
        pos += 4;
        System.arraycopy(value, 0, buf, pos, len);
        this.position = pos + len;
        return this;
    }

    public FastNbtWriter putIntArray(String name, int[] value) {
        int len = value.length;
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_INT_ARRAY, name, 4 + (len << 2));
        byte[] buf = this.buffer;
        int pos = this.position;
        INT_BE.set(buf, pos, len);
        pos += 4;
        for (int v : value) {
            INT_BE.set(buf, pos, v);
            pos += 4;
        }
        this.position = pos;
        return this;
    }

    public FastNbtWriter putLongArray(String name, long[] value) {
        int len = value.length;
        writeNamedHeaderAndPayloadSize(NbtConstants.TAG_LONG_ARRAY, name, 4 + (len << 3));
        byte[] buf = this.buffer;
        int pos = this.position;
        INT_BE.set(buf, pos, len);
        pos += 4;
        for (long v : value) {
            LONG_BE.set(buf, pos, v);
            pos += 8;
        }
        this.position = pos;
        return this;
    }

    // List element writers (without tag id and name)
    public FastNbtWriter addByte(byte value) {
        recordListElement(NbtConstants.TAG_BYTE);
        writeByte(value);
        return this;
    }

    public FastNbtWriter addShort(short value) {
        recordListElement(NbtConstants.TAG_SHORT);
        writeShort(value);
        return this;
    }

    public FastNbtWriter addInt(int value) {
        recordListElement(NbtConstants.TAG_INT);
        writeInt(value);
        return this;
    }

    public FastNbtWriter addLong(long value) {
        recordListElement(NbtConstants.TAG_LONG);
        writeLong(value);
        return this;
    }

    public FastNbtWriter addFloat(float value) {
        recordListElement(NbtConstants.TAG_FLOAT);
        writeInt(Float.floatToRawIntBits(value));
        return this;
    }

    public FastNbtWriter addDouble(double value) {
        recordListElement(NbtConstants.TAG_DOUBLE);
        writeLong(Double.doubleToRawLongBits(value));
        return this;
    }

    public FastNbtWriter addString(String value) {
        recordListElement(NbtConstants.TAG_STRING);
        writeUtf(value);
        return this;
    }

    public FastNbtWriter addByteArray(byte[] value) {
        recordListElement(NbtConstants.TAG_BYTE_ARRAY);
        int len = value.length;
        ensureCapacity(this.position + 4 + len);
        byte[] buf = this.buffer;
        int pos = this.position;
        INT_BE.set(buf, pos, len);
        pos += 4;
        System.arraycopy(value, 0, buf, pos, len);
        this.position = pos + len;
        return this;
    }

    public FastNbtWriter addIntArray(int[] value) {
        recordListElement(NbtConstants.TAG_INT_ARRAY);
        int len = value.length;
        ensureCapacity(this.position + 4 + (len << 2));
        byte[] buf = this.buffer;
        int pos = this.position;
        INT_BE.set(buf, pos, len);
        pos += 4;
        for (int v : value) {
            INT_BE.set(buf, pos, v);
            pos += 4;
        }
        this.position = pos;
        return this;
    }

    public FastNbtWriter addLongArray(long[] value) {
        recordListElement(NbtConstants.TAG_LONG_ARRAY);
        int len = value.length;
        ensureCapacity(this.position + 4 + (len << 3));
        byte[] buf = this.buffer;
        int pos = this.position;
        INT_BE.set(buf, pos, len);
        pos += 4;
        for (long v : value) {
            LONG_BE.set(buf, pos, v);
            pos += 8;
        }
        this.position = pos;
        return this;
    }

    public FastNbtWriter writeRaw(byte[] bytes) {
        writeBytes(bytes, 0, bytes.length);
        return this;
    }

    public FastNbtWriter reset() {
        this.position = 0;
        this.listDepth = 0;
        return this;
    }

    public int size() {
        return position;
    }

    public byte[] buffer() {
        return buffer;
    }

    public byte[] toByteArray() {
        byte[] copy = new byte[position];
        System.arraycopy(buffer, 0, copy, 0, position);
        return copy;
    }

    protected void writeNamedHeaderAndPayloadSize(byte tagId, String name, int payloadSize) {
        int strLen = name.length();
        boolean ascii = isModifiedUtfAscii(name, strLen);
        int utfLen = ascii ? strLen : modifiedUtfLength(name, strLen);
        if (utfLen > 0xFFFF) {
            throw new IllegalArgumentException("Encoded string length exceeds 65535 bytes: " + utfLen);
        }

        ensureCapacity(this.position + 3 + utfLen + payloadSize);

        byte[] buf = this.buffer;
        int pos = this.position;
        buf[pos++] = tagId;
        SHORT_BE.set(buf, pos, (short) utfLen);
        pos += 2;

        if (ascii) {
            writeAsciiChars(buf, pos, name, strLen);
        } else {
            writeRawUtfChars(buf, pos, name, strLen);
        }
        this.position = pos + utfLen;
    }

    protected void writeAnonymousHeader(byte tagId) {
        if (listDepth > 0) {
            recordListElement(tagId);
        }
    }

    private void pushList(byte expectedType, int countOffset) {
        if (listDepth >= listStack.length) {
            int newCap = listStack.length << 1;
            long[] newStack = new long[newCap];
            int[] newCounts = new int[newCap];
            System.arraycopy(listStack, 0, newStack, 0, listStack.length);
            System.arraycopy(listCounts, 0, newCounts, 0, listCounts.length);
            listStack = newStack;
            listCounts = newCounts;
        }
        listStack[listDepth] = (((long) expectedType) << 32) | (countOffset & 0xFFFFFFFFL);
        listCounts[listDepth] = 0;
        listDepth++;
    }

    private void recordListElement(byte tagId) {
        if (listDepth <= 0) {
            return;
        }
        byte expectedType = (byte) (listStack[listDepth - 1] >>> 32);
        if (expectedType != 0 && expectedType != tagId) {
            throw new IllegalArgumentException("List element type mismatch: expected " + expectedType + ", got " + tagId);
        }
        listCounts[listDepth - 1]++;
    }

    protected void writeByte(int value) {
        ensureCapacity(this.position + 1);
        this.buffer[this.position++] = (byte) value;
    }

    protected void writeShort(int value) {
        ensureCapacity(this.position + 2);
        SHORT_BE.set(this.buffer, this.position, (short) value);
        this.position += 2;
    }

    protected void writeInt(int value) {
        ensureCapacity(this.position + 4);
        INT_BE.set(this.buffer, this.position, value);
        this.position += 4;
    }

    protected void writeLong(long value) {
        ensureCapacity(this.position + 8);
        LONG_BE.set(this.buffer, this.position, value);
        this.position += 8;
    }

    protected void writeBytes(byte[] src, int offset, int length) {
        ensureCapacity(this.position + length);
        System.arraycopy(src, offset, this.buffer, this.position, length);
        this.position += length;
    }

    protected void writeUtf(String s) {
        int strLen = s.length();
        boolean ascii = isModifiedUtfAscii(s, strLen);
        int utfLen = ascii ? strLen : modifiedUtfLength(s, strLen);
        if (utfLen > 0xFFFF) {
            throw new IllegalArgumentException("Encoded string length exceeds 65535 bytes: " + utfLen);
        }

        ensureCapacity(this.position + 2 + utfLen);
        byte[] buf = this.buffer;
        int pos = this.position;
        SHORT_BE.set(buf, pos, (short) utfLen);
        pos += 2;

        if (ascii) {
            writeAsciiChars(buf, pos, s, strLen);
        } else {
            writeRawUtfChars(buf, pos, s, strLen);
        }
        this.position = pos + utfLen;
    }

    private static boolean isModifiedUtfAscii(String s, int len) {
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            // In Modified UTF-8, null character (0x0000) is encoded as 2 bytes (0xC0, 0x80)
            if (c == 0 || c > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static void writeAsciiChars(byte[] buf, int pos, String s, int len) {
        for (int i = 0; i < len; i++) {
            buf[pos + i] = (byte) s.charAt(i);
        }
    }

    private static void writeRawUtfChars(byte[] buf, int pos, String s, int len) {
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                buf[pos++] = (byte) c;
            } else if (c <= 0x07FF) {
                buf[pos++] = (byte) (0xC0 | ((c >> 6) & 0x1F));
                buf[pos++] = (byte) (0x80 | (c & 0x3F));
            } else {
                buf[pos++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
                buf[pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                buf[pos++] = (byte) (0x80 | (c & 0x3F));
            }
        }
    }

    private static int modifiedUtfLength(String s, int len) {
        int utfLen = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                utfLen += 1;
            } else if (c <= 0x07FF) {
                utfLen += 2;
            } else {
                utfLen += 3;
            }
        }
        return utfLen;
    }

    protected void ensureCapacity(int minCapacity) {
        if (minCapacity < 0) {
            throw new OutOfMemoryError("Required capacity is too large");
        }
        if (minCapacity <= buffer.length) {
            return;
        }

        int newCapacity = buffer.length << 1;
        if (newCapacity < 0 || newCapacity < minCapacity) {
            newCapacity = minCapacity;
        }

        byte[] newBuffer = new byte[newCapacity];
        System.arraycopy(buffer, 0, newBuffer, 0, position);
        this.buffer = newBuffer;
    }
}
