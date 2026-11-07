package io.github.kieranwaugh.airplay2.nowplaying;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Apple binary property list ("bplist00") parser.
 * Returns Map, List, String, Long, Double, Boolean, byte[], or Date (as Double seconds since 1970).
 */
public final class BPlist {
    private final byte[] b;
    private final int offsetSize, refSize;
    private final long[] offsets;

    private BPlist(byte[] data) {
        b = data;
        if (data.length < 40 || !new String(data, 0, 8, StandardCharsets.US_ASCII).startsWith("bplist0")) {
            throw new IllegalArgumentException("not a binary plist");
        }
        int t = data.length - 32;
        offsetSize = data[t + 6] & 0xff;
        refSize = data[t + 7] & 0xff;
        int numObjects = (int) readUInt(t + 8, 8);
        long offsetTable = readUInt(t + 24, 8);
        if (numObjects < 0 || numObjects > 1_000_000) throw new IllegalArgumentException("bad object count");
        offsets = new long[numObjects];
        for (int i = 0; i < numObjects; i++) {
            offsets[i] = readUInt((int) offsetTable + i * offsetSize, offsetSize);
        }
    }

    /** Parses a binary plist and returns its top object. */
    public static Object parse(byte[] data) {
        BPlist p = new BPlist(data);
        long top = p.readUInt(data.length - 32 + 16, 8);
        return p.object((int) top, 0);
    }

    private long readUInt(int pos, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) v = (v << 8) | (b[pos + i] & 0xff);
        return v;
    }

    private int[] lengthAndStart(int pos) {
        int marker = b[pos] & 0xff;
        int len = marker & 0x0f;
        int start = pos + 1;
        if (len == 0x0f) {
            int intMarker = b[start] & 0xff;
            int n = 1 << (intMarker & 0x0f);
            len = (int) readUInt(start + 1, n);
            start = start + 1 + n;
        }
        return new int[] {len, start};
    }

    private Object object(int ref, int depth) {
        if (depth > 64) throw new IllegalArgumentException("plist too deep");
        int pos = (int) offsets[ref];
        int marker = b[pos] & 0xff;
        int type = marker >> 4;
        switch (type) {
            case 0x0:
                if (marker == 0x08) return Boolean.FALSE;
                if (marker == 0x09) return Boolean.TRUE;
                return null;
            case 0x1: {
                // 1-, 2- and 4-byte ints are unsigned; 8-byte ints are signed, which readUInt's bit pattern already gives.
                return readUInt(pos + 1, 1 << (marker & 0x0f));
            }
            case 0x2: {
                int n = 1 << (marker & 0x0f);
                long bits = readUInt(pos + 1, n);
                return n == 4 ? (double) Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
            }
            case 0x3: {
                double appleSecs = Double.longBitsToDouble(readUInt(pos + 1, 8));
                return new Date(appleSecs + 978307200.0);
            }
            case 0x4: {
                int[] ls = lengthAndStart(pos);
                byte[] out = new byte[ls[0]];
                System.arraycopy(b, ls[1], out, 0, ls[0]);
                return out;
            }
            case 0x5: {
                int[] ls = lengthAndStart(pos);
                return new String(b, ls[1], ls[0], StandardCharsets.US_ASCII);
            }
            case 0x6: {
                int[] ls = lengthAndStart(pos);
                return new String(b, ls[1], ls[0] * 2, StandardCharsets.UTF_16BE);
            }
            case 0x7: {
                int[] ls = lengthAndStart(pos);
                return new String(b, ls[1], ls[0], StandardCharsets.UTF_8);
            }
            case 0x8:
                return readUInt(pos + 1, (marker & 0x0f) + 1);
            case 0xA:
            case 0xC: {
                int[] ls = lengthAndStart(pos);
                List<Object> list = new ArrayList<>(ls[0]);
                for (int i = 0; i < ls[0]; i++) {
                    list.add(object((int) readUInt(ls[1] + i * refSize, refSize), depth + 1));
                }
                return list;
            }
            case 0xD: {
                int[] ls = lengthAndStart(pos);
                Map<String, Object> map = new HashMap<>();
                int n = ls[0];
                for (int i = 0; i < n; i++) {
                    Object k = object((int) readUInt(ls[1] + i * refSize, refSize), depth + 1);
                    Object v = object((int) readUInt(ls[1] + (n + i) * refSize, refSize), depth + 1);
                    map.put(String.valueOf(k), v);
                }
                return map;
            }
            default:
                return null;
        }
    }

    /** A plist date, as seconds since the Unix epoch. */
    public static final class Date {
        public final double unixSeconds;
        Date(double s) { unixSeconds = s; }
        @Override public String toString() { return "Date(" + unixSeconds + ")"; }
    }
}
