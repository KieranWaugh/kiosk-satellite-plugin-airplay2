package io.github.kieranwaugh.airplay2;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Input from the network that must not hurt Kiosk Satellite: hostile binary plists (any device on
 * the network can send one through shairport-sync as ssnc/copl), floods of metadata chunks, and
 * mDNS messages from other apps. args[0] is the fixtures folder from tools/test.py. Run with java -ea.
 */
public class HardeningTest {
    public static void main(String[] args) throws Exception {
        byte[] real = Files.readAllBytes(Paths.get(args[0], "first.bplist"));
        assert BPlist.parse(real) instanceof Map : "a real Now Playing message still parses";

        hostilePlists();
        fuzz(real);
        chunks();
        mdns();
        System.out.println("HardeningTest passed");
    }

    /** Builds a bplist00 from objects (each already encoded) with 1-byte refs and 4-byte offsets. */
    static byte[] plist(List<byte[]> objects, int top) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("bplist00".getBytes(StandardCharsets.US_ASCII), 0, 8);
        int[] offsets = new int[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = out.size();
            out.write(objects.get(i), 0, objects.get(i).length);
        }
        int table = out.size();
        for (int o : offsets) out.write(be(o, 4), 0, 4);
        byte[] trailer = new byte[32];
        trailer[6] = 4; // offset size
        trailer[7] = 1; // ref size
        System.arraycopy(be(objects.size(), 8), 0, trailer, 8, 8);
        System.arraycopy(be(top, 8), 0, trailer, 16, 8);
        System.arraycopy(be(table, 8), 0, trailer, 24, 8);
        out.write(trailer, 0, 32);
        return out.toByteArray();
    }

    static byte[] be(long v, int n) {
        byte[] b = new byte[n];
        for (int i = n - 1; i >= 0; i--, v >>>= 8) b[i] = (byte) v;
        return b;
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    static List<byte[]> list(byte[]... objects) {
        List<byte[]> l = new ArrayList<>();
        for (byte[] o : objects) l.add(o);
        return l;
    }

    /** Parsing must fail with IllegalArgumentException, quickly. */
    static void rejected(String what, byte[] data) {
        long t = System.nanoTime();
        try {
            Object o = BPlist.parse(data);
            throw new AssertionError(what + ": parsed as " + o);
        } catch (IllegalArgumentException expected) {
            // good
        }
        long ms = (System.nanoTime() - t) / 1_000_000;
        assert ms < 2000 : what + " took " + ms + " ms";
    }

    static void hostilePlists() {
        // Lengths near 2^31 that the data doesn't have (the crash the review found).
        rejected("huge data", plist(list(cat(new byte[] {0x4f, 0x12}, be(0x7fffffffL, 4))), 0));
        rejected("huge array", plist(list(cat(new byte[] {(byte) 0xaf, 0x12}, be(0x7fffffffL, 4))), 0));
        rejected("huge dict", plist(list(cat(new byte[] {(byte) 0xdf, 0x12}, be(0x7fffffffL, 4))), 0));
        rejected("huge UTF-16 string", plist(list(cat(new byte[] {0x6f, 0x12}, be(0x40000000L, 4))), 0));
        rejected("negative 8-byte length", plist(list(cat(new byte[] {0x4f, 0x13}, be(-1L, 8))), 0));
        rejected("16-byte integer", plist(list(cat(new byte[] {0x14}, new byte[16])), 0));

        // Every level is an array of 16 references to the next: 16^20 objects if expanded.
        List<byte[]> fan = new ArrayList<>();
        for (int level = 0; level < 20; level++) {
            byte[] a = new byte[3 + 16]; // array, length 16 as a following int
            a[0] = (byte) 0xaf;
            a[1] = 0x10;
            a[2] = 16;
            for (int i = 0; i < 16; i++) a[3 + i] = (byte) (level + 1);
            fan.add(a);
        }
        fan.add(new byte[] {0x10, 0x01}); // the int at the bottom
        rejected("exponential fan-out", plist(fan, 0));

        // A dictionary whose key and value at every level are the next level: 2^depth.
        List<byte[]> dicts = new ArrayList<>();
        for (int level = 0; level < 30; level++) dicts.add(new byte[] {(byte) 0xd1, (byte) (level + 1), (byte) (level + 1)});
        dicts.add(new byte[] {0x50}); // ""
        rejected("dictionary sharing one child at every level", plist(dicts, 0));

        // One 100 KB data object referenced 100 times: 10 MB of copies.
        byte[] arr = new byte[3 + 100];
        arr[0] = (byte) 0xaf;
        arr[1] = 0x10;
        arr[2] = 100;
        for (int i = 0; i < 100; i++) arr[3 + i] = 1;
        rejected("shared big data", plist(list(arr, cat(new byte[] {0x4f, 0x12}, be(100_000, 4), new byte[100_000])), 0));

        // Bad trailers and references.
        byte[] ok = plist(list(new byte[] {0x09}), 0);
        assert Boolean.TRUE.equals(BPlist.parse(ok));
        byte[] bad = ok.clone();
        bad[bad.length - 32 + 6] = 9; // offset size 9
        rejected("offset size 9", bad);
        bad = ok.clone();
        System.arraycopy(be(5, 8), 0, bad, bad.length - 16, 8); // top object 5 of 1
        rejected("top out of range", bad);
        bad = ok.clone();
        System.arraycopy(be(1L << 40, 8), 0, bad, bad.length - 24, 8); // 2^40 objects
        rejected("object count", bad);
        bad = ok.clone();
        System.arraycopy(be(bad.length, 8), 0, bad, bad.length - 8, 8); // offset table past the end
        rejected("offset table", bad);
        rejected("array referring past the objects", plist(list(new byte[] {(byte) 0xa1, 7}), 0));
        rejected("object referring to itself", plist(list(new byte[] {(byte) 0xa1, 0}), 0));
        rejected("truncated", java.util.Arrays.copyOf("bplist00".getBytes(StandardCharsets.US_ASCII), 20));
    }

    /** Random corruptions of a real message: each parses or is rejected, nothing else. */
    static void fuzz(byte[] real) {
        Random random = new Random(42);
        for (int i = 0; i < 30000; i++) {
            byte[] d = real.clone();
            int flips = 1 + random.nextInt(8);
            for (int j = 0; j < flips; j++) {
                int pos = random.nextInt(4) == 0 ? d.length - 1 - random.nextInt(Math.min(64, d.length)) : random.nextInt(d.length);
                d[pos] = (byte) random.nextInt(256);
            }
            try {
                BPlist.parse(d);
            } catch (IllegalArgumentException fine) {
                // rejected
            } catch (Throwable t) {
                throw new AssertionError("corruption " + i + " threw " + t, t);
            }
        }
    }

    static byte[] chunk(int index, int count, String code, byte[] part) {
        byte[] out = new byte[24 + part.length];
        System.arraycopy("ssncchnk".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 8);
        System.arraycopy(be(index, 4), 0, out, 8, 4);
        System.arraycopy(be(count, 4), 0, out, 12, 4);
        System.arraycopy("ssnc".getBytes(StandardCharsets.US_ASCII), 0, out, 16, 4);
        System.arraycopy(code.getBytes(StandardCharsets.US_ASCII), 0, out, 20, 4);
        System.arraycopy(part, 0, out, 24, part.length);
        return out;
    }

    static void chunks() {
        List<Map<String, Object>> states = new ArrayList<>();
        NowPlaying np = new NowPlaying(states::add, () -> 1762560000.0);
        // Many items that never complete: only a few are kept.
        for (int i = 0; i < 1000; i++) np.onPacket(chunk(0, 2, String.format("c%03d", i % 1000), new byte[100]));
        assert np.partialItems() <= NowPlaying.MAX_PARTIAL_ITEMS : np.partialItems();
        // One item bigger than the cap is dropped once it passes it.
        byte[] big = new byte[60000];
        for (int i = 0; i < 64; i++) np.onPacket(chunk(i, 64, "copl", big));
        assert states.isEmpty() : "an oversized item must not be delivered";
        // A hostile plist in chunks is rejected without trouble.
        byte[] bomb = plist(list(cat(new byte[] {0x4f, 0x12}, be(0x7fffffffL, 4))), 0);
        np.onPacket(chunk(0, 1, "copl", bomb));
        assert states.isEmpty();
    }

    static void mdns() {
        String token = "0123456789abcdef0123456789abcdef";
        assert "REG 1 7000 _raop._tcp 41 -".equals(MdnsBridge.authenticated(token + " REG 1 7000 _raop._tcp 41 -", token));
        assert MdnsBridge.authenticated("REG 1 7000 _raop._tcp 41 -", token) == null : "no token";
        assert MdnsBridge.authenticated("0123456789abcdef0123456789abcdee REG 1", token) == null : "wrong token";
        assert MdnsBridge.authenticated(token + "0 REG 1", token) == null : "longer token";
        assert MdnsBridge.authenticated("x", token) == null;
        assert MdnsBridge.authenticated(" REG 1", "") == null : "no token set yet";
        assert MdnsBridge.SERVICE_TYPES.contains("_airplay._tcp") && MdnsBridge.SERVICE_TYPES.contains("_raop._tcp")
                && MdnsBridge.SERVICE_TYPES.size() == 2;
    }
}
