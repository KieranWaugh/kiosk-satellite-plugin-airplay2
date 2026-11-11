package io.github.kieranwaugh.airplay2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Apple binary property list ("bplist00") parser.
 * Returns Map, List, String, Long, Double, Boolean, byte[], or Date (as Double seconds since 1970).
 *
 * The input comes from the network (an AirPlay sender's Now Playing info, passed through by
 * shairport-sync), so nothing in it is trusted: every length and offset is checked against the
 * data before anything is allocated, and the number of objects built and the bytes they copy are
 * capped, so a plist that refers to the same child many times can't grow without bound. Anything
 * malformed throws IllegalArgumentException.
 */
final class BPlist {
    /** At most this many objects are built from one plist, counting each reference again. */
    static final int MAX_OBJECTS = 20_000;
    /** Strings and data copied out of one plist may total at most this many bytes. */
    static final long MAX_COPIED_BYTES = 4L << 20;
    static final int MAX_DEPTH = 32;

    private final byte[] b;
    private final int trailer; // where the 32-byte trailer starts; objects and offsets lie before it
    private final int offsetSize, refSize, numObjects, offsetTable;
    private int built;
    private long copied;

    private BPlist(byte[] data) {
        b = data;
        if (data.length < 8 + 32 || !new String(data, 0, 7, StandardCharsets.US_ASCII).equals("bplist0")) {
            throw new IllegalArgumentException("not a binary plist");
        }
        trailer = data.length - 32;
        offsetSize = data[trailer + 6] & 0xff;
        refSize = data[trailer + 7] & 0xff;
        if (offsetSize < 1 || offsetSize > 8 || refSize < 1 || refSize > 8) throw new IllegalArgumentException("bad trailer");
        long objects = readUInt(trailer + 8, 8);
        long table = readUInt(trailer + 24, 8);
        // Each object takes at least one byte, so there can't be more objects than bytes.
        if (objects < 1 || objects > trailer) throw new IllegalArgumentException("bad object count");
        if (table < 8 || table + objects * offsetSize > trailer) throw new IllegalArgumentException("bad offset table");
        numObjects = (int) objects;
        offsetTable = (int) table;
    }

    /** Parses a binary plist and returns its top object. Throws IllegalArgumentException if it's malformed. */
    public static Object parse(byte[] data) {
        try {
            BPlist p = new BPlist(data);
            return p.object(p.ref(p.readUInt(p.trailer + 16, 8)), 0);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) { // a check missed below: still only "malformed"
            throw new IllegalArgumentException("malformed plist: " + e, e);
        }
    }

    /** Reads a big-endian unsigned number of 1 to 8 bytes that must lie before the trailer's end. */
    private long readUInt(long pos, int size) {
        if (size < 1 || size > 8 || pos < 0 || pos + size > b.length) throw new IllegalArgumentException("read out of range");
        long v = 0;
        for (int i = 0; i < size; i++) v = (v << 8) | (b[(int) pos + i] & 0xff);
        return v;
    }

    /** An object reference, checked. */
    private int ref(long r) {
        if (r < 0 || r >= numObjects) throw new IllegalArgumentException("bad object reference");
        return (int) r;
    }

    /** Where object ref starts, checked. */
    private int offsetOf(int ref) {
        long pos = readUInt(offsetTable + (long) ref * offsetSize, offsetSize);
        if (pos < 8 || pos >= trailer) throw new IllegalArgumentException("bad object offset");
        return (int) pos;
    }

    /**
     * An object's count (bytes, characters or entries) and where its contents start, checked so
     * that count * unitBytes bytes from there lie before the offset table's trailer.
     */
    private long[] lengthAndStart(int pos, int unitBytes) {
        int marker = b[pos] & 0xff;
        long len = marker & 0x0f;
        long start = pos + 1;
        if (len == 0x0f) {
            if (start >= trailer) throw new IllegalArgumentException("truncated length");
            int intMarker = b[(int) start] & 0xff;
            if ((intMarker >> 4) != 0x1) throw new IllegalArgumentException("bad length marker");
            int n = 1 << (intMarker & 0x0f);
            if (n > 8) throw new IllegalArgumentException("bad length size");
            len = readUInt(start + 1, n);
            start = start + 1 + n;
        }
        if (len < 0 || len > trailer || start + len * unitBytes > trailer) throw new IllegalArgumentException("length out of range");
        return new long[] {len, start};
    }

    private void copying(long bytes) {
        copied += bytes;
        if (copied > MAX_COPIED_BYTES) throw new IllegalArgumentException("plist expands too far");
    }

    private Object object(int ref, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("plist too deep");
        if (++built > MAX_OBJECTS) throw new IllegalArgumentException("too many objects in plist");
        int pos = offsetOf(ref);
        int marker = b[pos] & 0xff;
        int type = marker >> 4;
        switch (type) {
            case 0x0:
                if (marker == 0x08) return Boolean.FALSE;
                if (marker == 0x09) return Boolean.TRUE;
                return null;
            case 0x1: {
                int n = 1 << (marker & 0x0f);
                if (n > 8) throw new IllegalArgumentException("integer too long");
                // 1-, 2- and 4-byte ints are unsigned; 8-byte ints are signed, which readUInt's bit pattern already gives.
                return readUInt(pos + 1, n);
            }
            case 0x2: {
                int n = 1 << (marker & 0x0f);
                if (n != 4 && n != 8) throw new IllegalArgumentException("bad real");
                long bits = readUInt(pos + 1, n);
                return n == 4 ? (double) Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
            }
            case 0x3: {
                double appleSecs = Double.longBitsToDouble(readUInt(pos + 1, 8));
                return new Date(appleSecs + 978307200.0);
            }
            case 0x4: {
                long[] ls = lengthAndStart(pos, 1);
                copying(ls[0]);
                byte[] out = new byte[(int) ls[0]];
                System.arraycopy(b, (int) ls[1], out, 0, out.length);
                return out;
            }
            case 0x5: {
                long[] ls = lengthAndStart(pos, 1);
                copying(ls[0]);
                return new String(b, (int) ls[1], (int) ls[0], StandardCharsets.US_ASCII);
            }
            case 0x6: {
                long[] ls = lengthAndStart(pos, 2);
                copying(ls[0] * 2);
                return new String(b, (int) ls[1], (int) ls[0] * 2, StandardCharsets.UTF_16BE);
            }
            case 0x7: {
                long[] ls = lengthAndStart(pos, 1);
                copying(ls[0]);
                return new String(b, (int) ls[1], (int) ls[0], StandardCharsets.UTF_8);
            }
            case 0x8: {
                int n = (marker & 0x0f) + 1;
                if (n > 8) throw new IllegalArgumentException("UID too long");
                return readUInt(pos + 1, n);
            }
            case 0xA:
            case 0xC: {
                long[] ls = lengthAndStart(pos, refSize);
                int n = (int) ls[0];
                List<Object> list = new ArrayList<>(Math.min(n, 64));
                for (int i = 0; i < n; i++) {
                    list.add(object(ref(readUInt(ls[1] + (long) i * refSize, refSize)), depth + 1));
                }
                return list;
            }
            case 0xD: {
                long[] ls = lengthAndStart(pos, 2 * refSize);
                int n = (int) ls[0];
                Map<String, Object> map = new HashMap<>();
                for (int i = 0; i < n; i++) {
                    Object k = object(ref(readUInt(ls[1] + (long) i * refSize, refSize)), depth + 1);
                    Object v = object(ref(readUInt(ls[1] + (long) (n + i) * refSize, refSize)), depth + 1);
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
