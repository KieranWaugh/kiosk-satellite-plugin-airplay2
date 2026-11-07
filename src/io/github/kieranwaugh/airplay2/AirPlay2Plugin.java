package io.github.kieranwaugh.airplay2;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * AirPlay 2 Speaker: runs shairport-sync (AirPlay 2 receiver) and NQPTP (its PTP timing companion)
 * as helper processes, advertises the speaker with Android's mDNS, sends the track to the AirPlay
 * Now Playing companion app (bundled, installed by the plugin) for Kiosk Satellite's Now Playing, and applies Kiosk's media volume and
 * assistant ducking.
 */
public class AirPlay2Plugin implements KioskPlugin {
    private PluginHost host;
    private File dir;
    private Nqptp nqptp;
    private Shairport shairport;
    private MdnsBridge mdns;
    private Companion companion;
    private volatile boolean companionReady;
    private Thread supervisor;
    private volatile boolean running;

    // settings
    private volatile String speakerName = "Kiosk Satellite";
    private volatile double offsetMs;
    private volatile boolean followMediaVolume = true, statistics;
    private volatile double duckPercent = 5;

    // volume state
    private volatile double mediaPercent = 100;
    private volatile boolean assistantActive;

    private String lastStatus;

    @Override
    public void start(PluginHost host, Map<String, Object> settings) throws Exception {
        this.host = host;
        Log.setHost(host);
        Context context = appContext();
        dir = new File(context.getNoBackupFilesDir(), "airplay2-speaker");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("can't create " + dir);
        readSettings(settings);

        nqptp = new Nqptp(dir, host.nativeLibraryPath("nqptp"));
        shairport = new Shairport(dir, host.nativeLibraryPath("shairport"), deviceId());
        shairport.writeConfig(speakerName, offsetMs / 1000.0, statistics);
        mdns = new MdnsBridge(context);
        mdns.start();
        // Now Playing: shairport-sync sends track metadata to the companion app (see
        // Shairport.METADATA_PORT), which the plugin carries and installs.
        companion = new Companion(context, host.assetPath("nowplaying.apk"));
        Thread c = new Thread(() -> {
            try {
                Log.i(companion.ensure());
                companionReady = true;
            } catch (Exception e) {
                Log.w("Now Playing companion app", e);
            }
        }, "airplay2-companion");
        c.setDaemon(true);
        c.start();

        host.subscribe("device.volume");
        try {
            host.subscribe("voice.state");
        } catch (IllegalArgumentException e) {
            Log.i("this Kiosk Satellite has no voice.state event, so AirPlay won't duck for the assistant");
        }
        readMediaVolume();

        running = true;
        supervisor = new Thread(this::supervise, "airplay2-supervisor");
        supervisor.start();
        status("Starting", false);
    }

    @Override
    public void configure(Map<String, Object> settings) throws Exception {
        readSettings(settings);
        applyGain();
        if (shairport != null && shairport.writeConfig(speakerName, offsetMs / 1000.0, statistics)) {
            Log.i("settings changed; restarting shairport-sync");
            shairport.stop(); // the supervisor starts it again with the new config
            wake();
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {}

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if ("ks.device.volume".equals(event)) {
            readMediaVolume();
        } else if ("ks.voice.state".equals(event)) {
            Object s = payload != null ? payload.get("state") : null;
            assistantActive = s != null && !"idle".equals(s);
            applyGain();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (supervisor != null) supervisor.interrupt();
        // Stop both helpers in parallel; root NQPTP needs one more su call.
        Thread a = new Thread(() -> { if (shairport != null) shairport.stop(); });
        Thread b = new Thread(() -> { if (nqptp != null) nqptp.stop(); });
        a.start();
        b.start();
        if (mdns != null) mdns.stop();
        try {
            a.join(1500);
            b.join(1800);
        } catch (InterruptedException ignored) {
        }
        Log.setHost(null);
    }

    private void supervise() {
        int quickExits = 0;
        long startedAt = 0, companionCheckedAt = 0;
        while (running) {
            // Keep the Now Playing app's service running (Android may stop it); a no-op if it runs.
            if (companionReady && System.currentTimeMillis() - companionCheckedAt > 60000) {
                companionCheckedAt = System.currentTimeMillis();
                companion.startService();
            }
            try {
                nqptp.start();
                if (!shairport.isRunning()) {
                    if (startedAt != 0) {
                        long ran = System.currentTimeMillis() - startedAt;
                        quickExits = ran < 15000 ? quickExits + 1 : 0;
                        Log.i("shairport-sync stopped (exit " + shairport.exitCode() + ") after " + ran / 1000 + " s");
                        if (quickExits >= 3) {
                            status("shairport-sync keeps stopping:\n" + shairport.lastLines(), true);
                            sleep(Math.min(60000, 5000L * quickExits));
                            if (!running) break;
                        }
                    }
                    shairport.start();
                    startedAt = System.currentTimeMillis();
                    applyGain();
                }
                String how = nqptp.mode() == Nqptp.Mode.APP ? "no root needed" : "timing (NQPTP) runs as root";
                status("Running as \"" + speakerName + "\" (" + how + ")", false);
            } catch (Exception e) {
                status(e.getMessage() != null ? e.getMessage() : e.toString(), true);
                sleep(10000);
            }
            sleep(2000);
        }
    }

    private volatile boolean wakeRequested;

    /** Ends the supervisor's current wait early (after a settings change). */
    private void wake() { wakeRequested = true; }

    private void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (running && !wakeRequested && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return; // stop()
            }
        }
        wakeRequested = false;
    }

    private void readMediaVolume() {
        Map<String, Object> args = new HashMap<>();
        args.put("channel", "media");
        host.executeCommand("getVolume", args, (ok, data, error) -> {
            if (!ok) {
                Log.d("getVolume failed: " + error);
                return;
            }
            Object v = data instanceof Map ? ((Map<?, ?>) data).get("percent") : data;
            if (v instanceof Number) {
                mediaPercent = ((Number) v).doubleValue();
                applyGain();
            }
        });
    }

    private void applyGain() {
        Shairport s = shairport;
        if (s == null) return;
        s.setGain((float) gain(followMediaVolume, mediaPercent, assistantActive, duckPercent));
    }

    /** Media volume uses a cubic curve, like PulseAudio's percent scale; the duck level is linear. */
    static double gain(boolean followMediaVolume, double mediaPercent, boolean assistantActive, double duckPercent) {
        double g = 1.0;
        if (followMediaVolume) {
            double m = Math.max(0, Math.min(100, mediaPercent)) / 100.0;
            g = m * m * m;
        }
        if (assistantActive) g *= Math.max(0, Math.min(100, duckPercent)) / 100.0;
        return g;
    }

    private void readSettings(Map<String, Object> s) {
        if (s == null) return;
        Object v = s.get("speakerName");
        if (v instanceof String && !((String) v).trim().isEmpty()) speakerName = ((String) v).trim();
        v = s.get("latencyOffsetMs");
        if (v instanceof Number) offsetMs = ((Number) v).doubleValue();
        v = s.get("followMediaVolume");
        if (v instanceof Boolean) followMediaVolume = (Boolean) v;
        v = s.get("duckPercent");
        if (v instanceof Number) duckPercent = ((Number) v).doubleValue();
        v = s.get("statistics");
        if (v instanceof Boolean) statistics = (Boolean) v;
    }

    private void status(String message, boolean error) {
        if (message.equals(lastStatus)) return;
        lastStatus = message;
        if (error) Log.i(message);
        try {
            host.status(message.length() > 1000 ? message.substring(0, 1000) : message, error);
        } catch (Exception ignored) {
        }
    }

    /** A stable, random, locally administered hardware address: Android 11+ hides the real one. */
    private String deviceId() throws Exception {
        File f = new File(dir, "device-id");
        if (f.exists()) {
            String id = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim();
            if (id.matches("[0-9a-f]{12}")) return id;
        }
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        b[0] = (byte) ((b[0] & 0xfc) | 0x02); // locally administered, unicast
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.US_ASCII));
        return sb.toString();
    }

    /** Plugins run inside Kiosk Satellite's process; its Application is the Context we need. */
    private static Context appContext() throws Exception {
        return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
    }
}
