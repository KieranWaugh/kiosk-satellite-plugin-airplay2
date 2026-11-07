package io.github.kieranwaugh.airplay2.nowplaying;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Companion app for the AirPlay 2 Speaker plugin. Receives shairport-sync's UDP metadata on
 * 127.0.0.1:5555 and mirrors it into an Android MediaSession, which Kiosk Satellite's Local Media
 * Session follows. (Kiosk ignores sessions from its own process, so the plugin can't do this itself.)
 */
public class NowPlayingService extends Service {
    private static final String TAG = "AirPlayNowPlaying";
    private static final int PORT = 5555;
    private static final String NPI = "kMRMediaRemoteNowPlayingInfo";

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSession session;
    /** The live session, for BrowseService (same process). */
    static volatile MediaSession current;
    private Thread receiver;
    private volatile boolean running;
    private DatagramSocket socket;

    // Chunked messages being reassembled, keyed by "type/code/count".
    private final Map<String, byte[][]> chunks = new HashMap<>();
    private final Map<String, Integer> chunkCounts = new HashMap<>();

    // Current now-playing info (main thread only).
    private final Map<String, Object> info = new HashMap<>();
    private double anchorElapsed;      // seconds into the track at anchorUnix
    private double anchorUnix;         // unix seconds of that anchor
    private double rate;               // 1.0 playing, 0.0 paused
    private boolean sessionPlaying;    // from shairport-sync's own play/pause events
    private String artKey;
    private Bitmap art;


    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                "np", "AirPlay now playing", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "np")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("AirPlay now playing")
                .setContentText("Shows AirPlay tracks in Kiosk Satellite's Now Playing")
                .setOngoing(true)
                .build();
        startForeground(1, n);

        session = new MediaSession(this, TAG);
        current = session;
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_NONE, 0, 0f).build());


        running = true;
        receiver = new Thread(this::receiveLoop, "airplay-metadata");
        receiver.start();
        new Thread(this::clockLoop, "airplay-clock").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (socket != null) socket.close();
        current = null;
        session.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void receiveLoop() {
        byte[] buf = new byte[70000];
        while (running) {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
                Log.i(TAG, "listening on 127.0.0.1:" + PORT);
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    if (p.getLength() < 8) continue;
                    byte[] d = new byte[p.getLength()];
                    System.arraycopy(buf, 0, d, 0, d.length);
                    onPacket(d);
                }
            } catch (Exception e) {
                if (!running) break;
                Log.w(TAG, "receive error, retrying", e);
                if (socket != null) socket.close();
                SystemClock.sleep(2000);
            }
        }
    }

    private static String ascii(byte[] d, int off) {
        return new String(d, off, 4, StandardCharsets.ISO_8859_1);
    }

    private static int u32(byte[] d, int off) {
        return ((d[off] & 0xff) << 24) | ((d[off + 1] & 0xff) << 16) | ((d[off + 2] & 0xff) << 8) | (d[off + 3] & 0xff);
    }

    /** Called on the receiver thread with one UDP packet. */
    private void onPacket(byte[] d) {
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

    /** One complete metadata item (receiver thread). */
    private void onItem(String type, String code, byte[] data) {
        if (!type.equals("ssnc")) return;
        switch (code) {
            case "copl": {
                Object top;
                try {
                    top = BPlist.parse(data);
                } catch (Exception e) {
                    Log.w(TAG, "bad plist: " + e);
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
                final Map<String, Object> fields = (Map<String, Object>) npi;
                final boolean replace = "replace".equals(policy);
                main.post(() -> applyInfo(fields, replace));
                break;
            }
            case "prsm":
            case "pbeg":
                main.post(() -> { sessionPlaying = true; publishState(); });
                break;
            case "paus":
                main.post(() -> { sessionPlaying = false; publishState(); });
                break;
            case "pend":
                main.post(this::endSession);
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

    // Seconds to add to this device's clock to get true time (measured by SNTP).
    // The iPhone's timestamps use true time; this Show's clock was found ~0.9 s slow.
    private static volatile double clockOffset;

    private static double nowUnix() {
        return System.currentTimeMillis() / 1000.0 + clockOffset;
    }

    /** Measures clockOffset against an NTP server every 30 minutes (receiver-independent thread). */
    private void clockLoop() {
        while (running) {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(5000);
                double best = Double.NaN, bestRtt = Double.MAX_VALUE;
                for (int i = 0; i < 4; i++) {
                    byte[] q = new byte[48];
                    q[0] = 0x23; // LI 0, version 4, mode 3 (client)
                    double t1 = System.currentTimeMillis() / 1000.0;
                    s.send(new DatagramPacket(q, 48, InetAddress.getByName("time.apple.com"), 123));
                    DatagramPacket r = new DatagramPacket(new byte[48], 48);
                    s.receive(r);
                    double t4 = System.currentTimeMillis() / 1000.0;
                    byte[] d = r.getData();
                    double t2 = ntpTime(d, 32), t3 = ntpTime(d, 40);
                    double rtt = (t4 - t1) - (t3 - t2);
                    if (rtt >= 0 && rtt < bestRtt) {
                        bestRtt = rtt;
                        best = ((t2 - t1) + (t3 - t4)) / 2;
                    }
                }
                if (!Double.isNaN(best) && Math.abs(best) < 3600) {
                    clockOffset = best;
                    Log.i(TAG, String.format("clock offset %.3f s (rtt %.3f s)", best, bestRtt));
                }
            } catch (Exception e) {
                Log.w(TAG, "SNTP failed: " + e);
            }
            SystemClock.sleep(running && clockOffset == 0 ? 60_000 : 30 * 60_000);
        }
    }

    private static double ntpTime(byte[] d, int off) {
        long secs = ((long) u32(d, off)) & 0xffffffffL;
        long frac = ((long) u32(d, off + 4)) & 0xffffffffL;
        return secs - 2208988800L + frac / 4294967296.0;
    }

    /** Main thread: merge an updateMRNowPlayingInfo message. */
    private void applyInfo(Map<String, Object> f, boolean replace) {
        if (replace && f.isEmpty()) return; // iPhone sends an empty one at connect
        // Advance the position anchor to this message's timestamp using the old rate.
        double ts = f.get(NPI + "Timestamp") instanceof BPlist.Date
                ? ((BPlist.Date) f.get(NPI + "Timestamp")).unixSeconds : nowUnix();
        if (f.containsKey(NPI + "ElapsedTime")) {
            anchorElapsed = num(f.get(NPI + "ElapsedTime"), 0);
        } else if (anchorUnix > 0) {
            anchorElapsed += (ts - anchorUnix) * rate;
        }
        anchorUnix = ts;
        if (f.containsKey(NPI + "PlaybackRate")) {
            rate = num(f.get(NPI + "PlaybackRate"), rate);
            sessionPlaying = rate > 0;
        }
        if (replace) info.clear();
        info.putAll(f);
        publishMetadata();
        publishState();
    }

    private String str(String key) {
        Object o = info.get(NPI + key);
        return o instanceof String ? (String) o : null;
    }

    private void publishMetadata() {
        Object artData = info.get(NPI + "ArtworkData");
        if (artData instanceof byte[]) {
            byte[] a = (byte[]) artData;
            String key = str("ArtworkIdentifier") + "/" + a.length;
            if (!key.equals(artKey)) {
                artKey = key;
                art = BitmapFactory.decodeByteArray(a, 0, a.length);
            }
        } else if (info.containsKey(NPI + "Title")) {
            art = null;
            artKey = null;
        }
        MediaMetadata.Builder b = new MediaMetadata.Builder();
        if (str("Title") != null) b.putString(MediaMetadata.METADATA_KEY_TITLE, str("Title"));
        if (str("Artist") != null) b.putString(MediaMetadata.METADATA_KEY_ARTIST, str("Artist"));
        if (str("Album") != null) b.putString(MediaMetadata.METADATA_KEY_ALBUM, str("Album"));
        if (str("Genre") != null) b.putString(MediaMetadata.METADATA_KEY_GENRE, str("Genre"));
        double dur = num(info.get(NPI + "Duration"), 0);
        if (dur > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (dur * 1000));
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(b.build());
        session.setActive(true);
    }

    private void publishState() {
        if (info.isEmpty()) return; // nothing to show until the first track info arrives
        double now = nowUnix();
        double pos = anchorElapsed + (anchorUnix > 0 ? (now - anchorUnix) * rate : 0);
        double dur = num(info.get(NPI + "Duration"), 0);
        if (pos < 0) pos = 0;
        if (dur > 0 && pos > dur) pos = dur;
        boolean playing = sessionPlaying && rate > 0;
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        (long) (pos * 1000), playing ? (float) rate : 0f, SystemClock.elapsedRealtime())
                .build());
        session.setActive(true);
    }

    private void endSession() {
        info.clear();
        art = null;
        artKey = null;
        rate = 0;
        anchorElapsed = 0;
        anchorUnix = 0;
        sessionPlaying = false;
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, 0, 0f).build());
        session.setMetadata(null);
        session.setActive(false);
    }
}
