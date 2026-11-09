package io.github.kieranwaugh.airplay2;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Feeds NowPlaying the datagrams shairport-sync sends. args[0] is a folder of binary plists written
 * by Python's plistlib (see tools/test.py), shaped like the iPhone's updateMRNowPlayingInfo:
 *   first.bplist   replace: title, artist, album, artwork, duration 200 s, elapsed 10 s at T, rate 1
 *   second.bplist  merge: a new title at T + 5, no elapsed time
 *   third.bplist   merge: rate 0 (paused), with no timestamp
 *   fourth.bplist  merge: rate 1 again, with no timestamp
 * with T = 1762560000. Run with java -ea.
 */
public class NowPlayingTest {
    static final double T = 1762560000.0;
    static double now;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        byte[] first = Files.readAllBytes(dir.resolve("first.bplist"));
        byte[] second = Files.readAllBytes(dir.resolve("second.bplist"));
        byte[] third = Files.readAllBytes(dir.resolve("third.bplist"));
        rateOne = Files.readAllBytes(dir.resolve("fourth.bplist"));
        List<Map<String, Object>> states = new ArrayList<>();
        NowPlaying np = new NowPlaying(states::add, () -> now);

        // The first message arrives 2.5 s after T by this device's clock (its clock is ahead, or
        // delivery was slow): that becomes the skew, so the position is 10 s exactly.
        now = T + 2.5;
        np.onPacket(item("copl", first));
        Map<String, Object> s = last(states);
        assert "playing".equals(s.get("state")) : s;
        assert "Song One".equals(s.get("title")) && "Artist".equals(s.get("artist")) && "Album".equals(s.get("album")) : s;
        assert Long.valueOf(200000).equals(s.get("durationMs")) : s;
        assert Long.valueOf(10000).equals(s.get("positionMs")) : s;
        assert ((byte[]) s.get("artwork")).length == 300 : s;
        assert ((List<?>) s.get("actions")).isEmpty() : "no remote control, so no buttons";

        // The second comes in chunks, 0.3 s slower than the quickest delivery: the skew stays, the
        // anchor moves 5 s on at rate 1, and the position is worked out on the iPhone's clock.
        now = T + 5 + 2.5 + 0.3;
        byte[] payload = second;
        int half = payload.length / 2;
        np.onPacket(chunk(1, 2, "copl", java.util.Arrays.copyOfRange(payload, half, payload.length)));
        assert states.size() == 1 : "nothing until every chunk is in";
        np.onPacket(chunk(0, 2, "copl", java.util.Arrays.copyOfRange(payload, 0, half)));
        s = last(states);
        assert "Song Two".equals(s.get("title")) : s;
        assert "Artist".equals(s.get("artist")) : "a merge keeps the rest";
        assert Math.abs((Long) s.get("positionMs") - 15300) <= 1 : s.get("positionMs");

        // No timestamp: the message counts as sent now on the iPhone's clock (T + 10.3), not on this
        // device's (T + 12.8). The anchor moves 5.3 s on at rate 1, then the rate drops to 0.
        now = T + 10 + 2.5 + 0.3;
        np.onPacket(item("copl", third));
        s = last(states);
        assert "paused".equals(s.get("state")) : s;
        assert Math.abs((Long) s.get("positionMs") - 20300) <= 1 : s.get("positionMs");
        now += 30;
        np.onPacket(item("prsm", new byte[0]));
        assert "paused".equals(last(states).get("state")) : "rate 0 keeps it paused";
        assert Math.abs((Long) last(states).get("positionMs") - 20300) <= 1 : "a paused position stays";
        np.onPacket(chunk(0, 1, "copl", rateOne)); // a message in a single chunk
        assert "playing".equals(last(states).get("state"));

        np.onPacket(item("paus", new byte[0]));
        assert "paused".equals(last(states).get("state"));
        np.onPacket(item("prsm", new byte[0]));
        assert "playing".equals(last(states).get("state"));
        np.onPacket(item("pend", new byte[0]));
        assert "idle".equals(last(states).get("state")) && last(states).size() == 1;

        // After the session ends nothing shows until track info arrives again.
        int n = states.size();
        np.onPacket(item("pbeg", new byte[0]));
        assert states.size() == n;
        np.onPacket(item("copl", new byte[] {1, 2, 3})); // not a plist: ignored
        np.onPacket(new byte[] {'c', 'o', 'r', 'e', 'a', 's', 'a', 'r', 'x'}); // another type: ignored
        assert states.size() == n;
        System.out.println("NowPlayingTest passed");
    }

    static byte[] rateOne;

    private static Map<String, Object> last(List<Map<String, Object>> states) {
        assert !states.isEmpty();
        return states.get(states.size() - 1);
    }

    private static byte[] item(String code, byte[] data) {
        byte[] out = new byte[8 + data.length];
        System.arraycopy("ssnc".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        System.arraycopy(code.getBytes(StandardCharsets.US_ASCII), 0, out, 4, 4);
        System.arraycopy(data, 0, out, 8, data.length);
        return out;
    }

    private static byte[] chunk(int index, int count, String code, byte[] part) {
        byte[] out = new byte[24 + part.length];
        System.arraycopy("ssncchnk".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 8);
        out[11] = (byte) index;
        out[15] = (byte) count;
        System.arraycopy("ssnc".getBytes(StandardCharsets.US_ASCII), 0, out, 16, 4);
        System.arraycopy(code.getBytes(StandardCharsets.US_ASCII), 0, out, 20, 4);
        System.arraycopy(part, 0, out, 24, part.length);
        return out;
    }
}
