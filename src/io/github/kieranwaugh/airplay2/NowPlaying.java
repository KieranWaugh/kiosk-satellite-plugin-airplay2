package io.github.kieranwaugh.airplay2;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * Turns shairport-sync's metadata into the player's Now Playing state (PluginPlayer.update).
 *
 * shairport-sync sends its metadata as UDP datagrams to 127.0.0.1:port(): "ssnc" items, long ones
 * split into "chnk" chunks. The track comes from the iPhone's updateMRNowPlayingInfo messages
 * (ssnc/copl, a binary plist), play and pause from ssnc/pbeg, prsm, paus and pend.
 *
 * The position: each message carries the elapsed time and the iPhone's clock when it was measured.
 * This device's clock can be off by a second or so, so the iPhone's clock is estimated as this
 * device's clock minus the smallest (arrival - message timestamp) seen in the session: a message
 * never arrives before it was sent, and the quickest one arrived almost at once.
 */
final class NowPlaying {
    private static final String NPI = "kMRMediaRemoteNowPlayingInfo";
    static final int MAX_ARTWORK = 1 << 20;

    private final Consumer<Map<String, Object>> sink;
    private final DoubleSupplier clock; // this device's clock, unix seconds
    private DatagramSocket socket;
    private Thread thread;
    private volatile boolean running;

    // Chunked items being put back together, keyed by "type/code/count" (receiver thread only).
    private final Map<String, byte[][]> chunks = new HashMap<>();
    private final Map<String, Integer> chunkCounts = new HashMap<>();

    // The track (guarded by this).
    private final Map<String, Object> info = new HashMap<>();
    private double anchorElapsed; // seconds into the track at anchorTime
    private double anchorTime;    // the iPhone's clock (unix seconds) at that point
    private double rate;          // 1.0 playing, 0.0 paused
    private boolean sessionPlaying;
    private double skew = Double.NaN; // this device's clock minus the iPhone's, plus the quickest delivery

    NowPlaying(Consumer<Map<String, Object>> sink, DoubleSupplier clock) {
        this.sink = sink;
        this.clock = clock;
    }

    NowPlaying(Consumer<Map<String, Object>> sink) {
        this(sink, () -> System.currentTimeMillis() / 1000.0);
    }

    /** Listens on a free port on 127.0.0.1; port() says which, for shairport-sync's config. */
    void start() throws SocketException {
        socket = new DatagramSocket(new InetSocketAddress(loopback(), 0));
        running = true;
        thread = new Thread(this::receiveLoop, "airplay2-metadata");
        thread.setDaemon(true);
        thread.start();
    }

    int port() { return socket.getLocalPort(); }

    void stop() {
        running = false;
        if (socket != null) socket.close();
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[70000];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                if (p.getLength() < 8) continue;
                byte[] d = new byte[p.getLength()];
                System.arraycopy(buf, 0, d, 0, d.length);
                onPacket(d);
            } catch (Exception e) {
                if (running) Log.w("metadata", e);
            }
        }
    }

    private static String ascii(byte[] d, int off) {
        return new String(d, off, 4, StandardCharsets.ISO_8859_1);
    }

    private static int u32(byte[] d, int off) {
        return ((d[off] & 0xff) << 24) | ((d[off + 1] & 0xff) << 16) | ((d[off + 2] & 0xff) << 8) | (d[off + 3] & 0xff);
    }

    /** One UDP datagram from shairport-sync. Package-private for the tests. */
    void onPacket(byte[] d) {
        String type = ascii(d, 0), code = ascii(d, 4);
        if (type.equals("ssnc") && code.equals("chnk")) {
            if (d.length < 24) return;
            int ix = u32(d, 8), count = u32(d, 12);
            String it = ascii(d, 16), ic = ascii(d, 20);
            if (count <= 0 || count > 64 || ix < 0 || ix >= count) return;
            String key = it + "/" + ic + "/" + count;
            byte[][] parts = chunks.get(key);
            if (parts == null) {
                parts = new byte[count][];
                chunks.put(key, parts);
                chunkCounts.put(key, 0);
            }
            if (parts[ix] == null) {
                byte[] part = new byte[d.length - 24];
                System.arraycopy(d, 24, part, 0, part.length);
                parts[ix] = part;
                chunkCounts.put(key, chunkCounts.get(key) + 1);
            }
            if (chunkCounts.get(key) == count) {
                chunks.remove(key);
                chunkCounts.remove(key);
                int total = 0;
                for (byte[] part : parts) total += part.length;
                byte[] whole = new byte[total];
                int off = 0;
                for (byte[] part : parts) {
                    System.arraycopy(part, 0, whole, off, part.length);
                    off += part.length;
                }
                onItem(it, ic, whole);
            }
            return;
        }
        byte[] data = new byte[d.length - 8];
        System.arraycopy(d, 8, data, 0, data.length);
        onItem(type, code, data);
    }

    private void onItem(String type, String code, byte[] data) {
        if (!type.equals("ssnc")) return;
        switch (code) {
            case "copl": {
                Object top;
                try {
                    top = BPlist.parse(data);
                } catch (Exception e) {
                    Log.d("bad plist: " + e);
                    return;
                }
                if (!(top instanceof Map)) return;
                Map<?, ?> m = (Map<?, ?>) top;
                if (!"updateMRNowPlayingInfo".equals(m.get("type"))) return;
                Object params = m.get("params");
                if (!(params instanceof Map)) return;
                Object npi = ((Map<?, ?>) params).get("params");
                Object policy = ((Map<?, ?>) params).get("mergePolicy");
                if (!(npi instanceof Map)) return;
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = (Map<String, Object>) npi;
                applyInfo(fields, "replace".equals(policy));
                break;
            }
            case "prsm":
            case "pbeg":
                setPlaying(true);
                break;
            case "paus":
                setPlaying(false);
                break;
            case "pend":
                end();
                break;
            default:
                break;
        }
    }

    private static double num(Object o, double dflt) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof Long) return ((Long) o).doubleValue();
        return dflt;
    }

    /** The iPhone's clock now, as best known. */
    private double senderNow() {
        double local = clock.getAsDouble();
        return Double.isNaN(skew) ? local : local - skew;
    }

    private synchronized void applyInfo(Map<String, Object> f, boolean replace) {
        if (replace && f.isEmpty()) return; // the iPhone sends an empty one when it connects
        double local = clock.getAsDouble();
        Object stamp = f.get(NPI + "Timestamp");
        double ts;
        if (stamp instanceof BPlist.Date) {
            ts = ((BPlist.Date) stamp).unixSeconds;
            double s = local - ts;
            if (Double.isNaN(skew) || s < skew) skew = s;
        } else {
            ts = senderNow();
        }
        // Move the anchor on to this message's time at the old rate, unless it says where it is.
        if (f.containsKey(NPI + "ElapsedTime")) {
            anchorElapsed = num(f.get(NPI + "ElapsedTime"), 0);
        } else if (anchorTime > 0) {
            anchorElapsed += (ts - anchorTime) * rate;
        }
        anchorTime = ts;
        if (f.containsKey(NPI + "PlaybackRate")) {
            rate = num(f.get(NPI + "PlaybackRate"), rate);
            sessionPlaying = rate > 0;
        }
        if (replace) info.clear();
        info.putAll(f);
        publish();
    }

    private synchronized void setPlaying(boolean playing) {
        sessionPlaying = playing;
        publish();
    }

    /** The sender went away: show nothing, and forget its clock. */
    synchronized void end() {
        info.clear();
        rate = 0;
        anchorElapsed = 0;
        anchorTime = 0;
        sessionPlaying = false;
        skew = Double.NaN;
        sink.accept(Collections.singletonMap("state", "idle"));
    }

    private String str(String key) {
        Object o = info.get(NPI + key);
        return o instanceof String ? (String) o : null;
    }

    private void publish() {
        if (info.isEmpty()) return; // nothing to show until the first track info arrives
        Map<String, Object> state = new HashMap<>();
        boolean playing = sessionPlaying && rate > 0;
        state.put("state", playing ? "playing" : "paused");
        putIf(state, "title", str("Title"));
        putIf(state, "artist", str("Artist"));
        putIf(state, "album", str("Album"));
        Object art = info.get(NPI + "ArtworkData");
        if (art instanceof byte[] && ((byte[]) art).length > 0 && ((byte[]) art).length <= MAX_ARTWORK) {
            state.put("artwork", art);
        }
        double dur = num(info.get(NPI + "Duration"), 0);
        if (dur > 0) state.put("durationMs", (long) (dur * 1000));
        double pos = anchorElapsed + (anchorTime > 0 ? (senderNow() - anchorTime) * rate : 0);
        if (pos < 0) pos = 0;
        if (dur > 0 && pos > dur) pos = dur;
        state.put("positionMs", (long) (pos * 1000));
        // AirPlay 2 gives a receiver no remote control, so Now Playing shows no buttons.
        state.put("actions", Collections.emptyList());
        sink.accept(state);
    }

    private static void putIf(Map<String, Object> m, String key, String value) {
        if (value != null) m.put(key, value.length() > 512 ? value.substring(0, 512) : value);
    }
}
