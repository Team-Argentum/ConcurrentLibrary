package net.sixik.concurrent_library.utils.nbt;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.BiConsumer;

public class FastNbtReader {

    private static final VarHandle SHORT_BE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_BE   = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_BE  = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private final byte[] buffer;
    private final int rootOffset;
    private final int rootLength;

    public FastNbtReader(byte[] buffer) {
        this(buffer, 0, buffer.length);
    }

    public FastNbtReader(byte[] buffer, int offset, int length) {
        this.buffer = buffer;
        this.rootOffset = offset;
        this.rootLength = length;
    }

    public FastCompound readRootCompound() {
        int pos = rootOffset;
        if (pos >= rootOffset + rootLength) {
            throw new IllegalArgumentException("Empty NBT buffer");
        }

        byte type = buffer[pos++];
        if (type != NbtConstants.TAG_COMPOUND) {
            throw new IllegalStateException("Root tag is not a compound tag, got type: " + type);
        }

        int nameLen = readUnsignedShort(buffer, pos);
        pos += 2 + nameLen; // skip root name

        return new FastCompound(buffer, pos);
    }

    public FastCompound readCompoundAt(int payloadOffset) {
        return new FastCompound(buffer, payloadOffset);
    }

    public static class FastCompound {
        private static final int SLOT_SIZE = 4;
        private static final int EMPTY = -1;

        private final byte[] buffer;
        private final int payloadStart;

        private int[] table;
        private int mask;
        private int entryCount;

        public FastCompound(byte[] buffer, int payloadStart) {
            this.buffer = buffer;
            this.payloadStart = payloadStart;
            buildIndex();
        }

        public boolean contains(String key) {
            return findSlot(key) >= 0;
        }

        public byte getType(String key) {
            int slot = findSlot(key);
            if (slot < 0) {
                return NbtConstants.TAG_END;
            }
            return (byte) table[slot + 3];
        }

        public byte getByte(String key, byte defaultValue) {
            int slot = findSlot(key);
            if (slot < 0) return defaultValue;
            int offset = table[slot + 2];
            return buffer[offset];
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            return getByte(key, (byte) (defaultValue ? 1 : 0)) != 0;
        }

        public short getShort(String key, short defaultValue) {
            int slot = findSlot(key);
            if (slot < 0) return defaultValue;
            int offset = table[slot + 2];
            return (short) SHORT_BE.get(buffer, offset);
        }

        public int getInt(String key, int defaultValue) {
            int slot = findSlot(key);
            if (slot < 0) return defaultValue;
            int offset = table[slot + 2];
            return (int) INT_BE.get(buffer, offset);
        }

        public long getLong(String key, long defaultValue) {
            int slot = findSlot(key);
            if (slot < 0) return defaultValue;
            int offset = table[slot + 2];
            return (long) LONG_BE.get(buffer, offset);
        }

        public float getFloat(String key, float defaultValue) {
            int slot = findSlot(key);
            if (slot < 0) return defaultValue;
            int offset = table[slot + 2];
            return Float.intBitsToFloat((int) INT_BE.get(buffer, offset));
        }

        public double getDouble(String key, double defaultValue) {
            int slot = findSlot(key);
            if (slot < 0) return defaultValue;
            int offset = table[slot + 2];
            return Double.longBitsToDouble((long) LONG_BE.get(buffer, offset));
        }

        public String getString(String key, String defaultValue) {
            int slot = findSlot(key);
            if (slot < 0) return defaultValue;
            int offset = table[slot + 2];
            int len = readUnsignedShort(buffer, offset);
            return decodeModifiedUtf(buffer, offset + 2, len);
        }

        public byte[] getByteArray(String key) {
            int slot = findSlot(key);
            if (slot < 0) return null;
            int offset = table[slot + 2];
            int len = (int) INT_BE.get(buffer, offset);
            byte[] arr = new byte[len];
            System.arraycopy(buffer, offset + 4, arr, 0, len);
            return arr;
        }

        public int[] getIntArray(String key) {
            int slot = findSlot(key);
            if (slot < 0) return null;
            int offset = table[slot + 2];
            int len = (int) INT_BE.get(buffer, offset);
            int[] arr = new int[len];
            int pos = offset + 4;
            for (int i = 0; i < len; i++, pos += 4) {
                arr[i] = (int) INT_BE.get(buffer, pos);
            }
            return arr;
        }

        public long[] getLongArray(String key) {
            int slot = findSlot(key);
            if (slot < 0) return null;
            int offset = table[slot + 2];
            int len = (int) INT_BE.get(buffer, offset);
            long[] arr = new long[len];
            int pos = offset + 4;
            for (int i = 0; i < len; i++, pos += 8) {
                arr[i] = (long) LONG_BE.get(buffer, pos);
            }
            return arr;
        }

        public FastCompound getCompound(String key) {
            int slot = findSlot(key);
            if (slot < 0) return null;
            int offset = table[slot + 2];
            return new FastCompound(buffer, offset);
        }

        public FastList getList(String key) {
            int slot = findSlot(key);
            if (slot < 0) return null;
            int offset = table[slot + 2];
            return new FastList(buffer, offset);
        }

        public int size() {
            return entryCount;
        }

        public void forEach(BiConsumer<String, Byte> consumer) {
            for (int i = 0; i < table.length; i += SLOT_SIZE) {
                if (table[i] != EMPTY) {
                    int nameOffset = table[i];
                    int nameLen = table[i + 1];
                    byte tagType = (byte) table[i + 3];
                    String name = decodeModifiedUtf(buffer, nameOffset, nameLen);
                    consumer.accept(name, tagType);
                }
            }
        }

        private void buildIndex() {
            int capacity = 16;
            this.table = new int[capacity * SLOT_SIZE];
            Arrays.fill(this.table, EMPTY);
            this.mask = capacity - 1;
            this.entryCount = 0;

            int pos = payloadStart;
            while (true) {
                byte tagType = buffer[pos++];
                if (tagType == NbtConstants.TAG_END) {
                    break;
                }

                int nameLen = readUnsignedShort(buffer, pos);
                pos += 2;
                int nameOffset = pos;
                pos += nameLen;

                int valueOffset = pos;
                pos = skipTagPayload(buffer, pos, tagType);

                putEntry(nameOffset, nameLen, valueOffset, tagType);
            }
        }

        private void putEntry(int nameOffset, int nameLen, int valueOffset, byte tagType) {
            if (entryCount >= (mask + 1) * 0.75) {
                rehash();
            }

            int hash = hashName(buffer, nameOffset, nameLen);
            int idx = (hash & mask) * SLOT_SIZE;

            while (table[idx] != EMPTY) {
                idx = ((idx / SLOT_SIZE + 1) & mask) * SLOT_SIZE;
            }

            table[idx] = nameOffset;
            table[idx + 1] = nameLen;
            table[idx + 2] = valueOffset;
            table[idx + 3] = tagType;
            entryCount++;
        }

        private void rehash() {
            int newCap = (mask + 1) << 1;
            int[] oldTable = table;
            table = new int[newCap * SLOT_SIZE];
            Arrays.fill(table, EMPTY);
            mask = newCap - 1;

            for (int i = 0; i < oldTable.length; i += SLOT_SIZE) {
                if (oldTable[i] != EMPTY) {
                    int nameOffset = oldTable[i];
                    int nameLen = oldTable[i + 1];
                    int valueOffset = oldTable[i + 2];
                    int tagType = oldTable[i + 3];

                    int hash = hashName(buffer, nameOffset, nameLen);
                    int idx = (hash & mask) * SLOT_SIZE;
                    while (table[idx] != EMPTY) {
                        idx = ((idx / SLOT_SIZE + 1) & mask) * SLOT_SIZE;
                    }
                    table[idx] = nameOffset;
                    table[idx + 1] = nameLen;
                    table[idx + 2] = valueOffset;
                    table[idx + 3] = tagType;
                }
            }
        }

        private int findSlot(String key) {
            int keyHash = hashString(key);
            int idx = (keyHash & mask) * SLOT_SIZE;

            while (table[idx] != EMPTY) {
                int nameOffset = table[idx];
                int nameLen = table[idx + 1];
                if (equalsKey(buffer, nameOffset, nameLen, key)) {
                    return idx;
                }
                idx = ((idx / SLOT_SIZE + 1) & mask) * SLOT_SIZE;
            }
            return -1;
        }
    }

    public static class FastList {
        private final byte[] buffer;
        private final byte elementType;
        private final int size;
        private final int[] elementOffsets;

        public FastList(byte[] buffer, int payloadStart) {
            this.buffer = buffer;
            this.elementType = buffer[payloadStart];
            this.size = (int) INT_BE.get(buffer, payloadStart + 1);

            int pos = payloadStart + 5;
            if (this.size > 0 && this.elementType != NbtConstants.TAG_END) {
                this.elementOffsets = new int[this.size];
                for (int i = 0; i < this.size; i++) {
                    this.elementOffsets[i] = pos;
                    pos = skipTagPayload(buffer, pos, elementType);
                }
            } else {
                this.elementOffsets = new int[0];
            }
        }

        public int size() {
            return size;
        }

        public byte getElementType() {
            return elementType;
        }

        public byte getByte(int index) {
            return buffer[elementOffsets[index]];
        }

        public short getShort(int index) {
            int offset = elementOffsets[index];
            return (short) SHORT_BE.get(buffer, offset);
        }

        public int getInt(int index) {
            return (int) INT_BE.get(buffer, elementOffsets[index]);
        }

        public long getLong(int index) {
            return (long) LONG_BE.get(buffer, elementOffsets[index]);
        }

        public float getFloat(int index) {
            return Float.intBitsToFloat((int) INT_BE.get(buffer, elementOffsets[index]));
        }

        public double getDouble(int index) {
            return Double.longBitsToDouble((long) LONG_BE.get(buffer, elementOffsets[index]));
        }

        public String getString(int index) {
            int offset = elementOffsets[index];
            int len = readUnsignedShort(buffer, offset);
            return decodeModifiedUtf(buffer, offset + 2, len);
        }

        public FastCompound getCompound(int index) {
            return new FastCompound(buffer, elementOffsets[index]);
        }
    }

    public static int skipTagPayload(byte[] buf, int pos, byte type) {
        return switch (type) {
            case NbtConstants.TAG_END -> pos;
            case NbtConstants.TAG_BYTE -> pos + 1;
            case NbtConstants.TAG_SHORT -> pos + 2;
            case NbtConstants.TAG_INT, NbtConstants.TAG_FLOAT -> pos + 4;
            case NbtConstants.TAG_LONG, NbtConstants.TAG_DOUBLE -> pos + 8;
            case NbtConstants.TAG_BYTE_ARRAY -> {
                int len = (int) INT_BE.get(buf, pos);
                yield pos + 4 + len;
            }
            case NbtConstants.TAG_STRING -> {
                int len = readUnsignedShort(buf, pos);
                yield pos + 2 + len;
            }
            case NbtConstants.TAG_LIST -> {
                byte elemType = buf[pos++];
                int count = (int) INT_BE.get(buf, pos);
                pos += 4;
                for (int i = 0; i < count; i++) {
                    pos = skipTagPayload(buf, pos, elemType);
                }
                yield pos;
            }
            case NbtConstants.TAG_COMPOUND -> {
                while (true) {
                    byte nestedType = buf[pos++];
                    if (nestedType == NbtConstants.TAG_END) {
                        break;
                    }
                    int nameLen = readUnsignedShort(buf, pos);
                    pos += 2 + nameLen;
                    pos = skipTagPayload(buf, pos, nestedType);
                }
                yield pos;
            }
            case NbtConstants.TAG_INT_ARRAY -> {
                int count = (int) INT_BE.get(buf, pos);
                yield pos + 4 + (count << 2);
            }
            case NbtConstants.TAG_LONG_ARRAY -> {
                int count = (int) INT_BE.get(buf, pos);
                yield pos + 4 + (count << 3);
            }
            default -> throw new IllegalArgumentException("Unknown tag type: " + type);
        };
    }

    private static int hashName(byte[] buf, int offset, int len) {
        int h = 0;
        for (int i = 0; i < len; i++) {
            h = 31 * h + (buf[offset + i] & 0xFF);
        }
        return h;
    }

    private static int hashString(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                h = 31 * h + c;
            } else if (c <= 0x07FF) {
                h = 31 * h + (0xC0 | ((c >> 6) & 0x1F));
                h = 31 * h + (0x80 | (c & 0x3F));
            } else {
                h = 31 * h + (0xE0 | ((c >> 12) & 0x0F));
                h = 31 * h + (0x80 | ((c >> 6) & 0x3F));
                h = 31 * h + (0x80 | (c & 0x3F));
            }
        }
        return h;
    }

    private static boolean equalsKey(byte[] buf, int offset, int len, String key) {
        int keyLen = key.length();
        int keyPos = 0;
        int bufPos = offset;
        int end = offset + len;

        while (bufPos < end && keyPos < keyLen) {
            char c = key.charAt(keyPos++);
            if (c >= 0x0001 && c <= 0x007F) {
                if (buf[bufPos++] != (byte) c) return false;
            } else if (c <= 0x07FF) {
                if (bufPos + 1 >= end) return false;
                if (buf[bufPos++] != (byte) (0xC0 | ((c >> 6) & 0x1F))) return false;
                if (buf[bufPos++] != (byte) (0x80 | (c & 0x3F))) return false;
            } else {
                if (bufPos + 2 >= end) return false;
                if (buf[bufPos++] != (byte) (0xE0 | ((c >> 12) & 0x0F))) return false;
                if (buf[bufPos++] != (byte) (0x80 | ((c >> 6) & 0x3F))) return false;
                if (buf[bufPos++] != (byte) (0x80 | (c & 0x3F))) return false;
            }
        }
        return bufPos == end && keyPos == keyLen;
    }

    public static String decodeModifiedUtf(byte[] buf, int offset, int len) {
        char[] chars = new char[len];
        int count = 0;
        int end = offset + len;
        int i = offset;

        while (i < end) {
            int b1 = buf[i++] & 0xFF;
            if ((b1 & 0x80) == 0) { // 1 byte
                chars[count++] = (char) b1;
            } else if ((b1 >> 5) == 6) { // 2 bytes: 110xxxxx 10xxxxxx
                int b2 = buf[i++] & 0xFF;
                chars[count++] = (char) (((b1 & 0x1F) << 6) | (b2 & 0x3F));
            } else if ((b1 >> 4) == 14) { // 3 bytes: 1110xxxx 10xxxxxx 10xxxxxx
                int b2 = buf[i++] & 0xFF;
                int b3 = buf[i++] & 0xFF;
                chars[count++] = (char) (((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F));
            } else {
                chars[count++] = (char) b1;
            }
        }
        return new String(chars, 0, count);
    }

    public static int readUnsignedShort(byte[] b, int off) {
        return (short) SHORT_BE.get(b, off) & 0xFFFF;
    }
}
