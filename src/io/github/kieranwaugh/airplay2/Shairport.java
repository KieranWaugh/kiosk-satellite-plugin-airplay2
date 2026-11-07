package io.github.kieranwaugh.airplay2;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * shairport-sync, the AirPlay 2 receiver, run as a separate process so a crash in it can't take
 * Kiosk Satellite down. It is a PIE executable packaged as libshairport.so and started through
 * /system/bin/linker (Android 10+ won't execute files from app storage directly).
 *
 * The plugin talks to it three ways: the config file it writes, "gain x" lines on its stdin (the
 * AAudio backend's extra volume, for Kiosk's media volume and ducking), and two local UDP ports
 * it sends to (mDNS records for MdnsBridge, track metadata for the companion app).
 */
final class Shairport {
    static final int MDNS_PORT = 5566;
    /**
     * Track metadata goes to the AirPlay Now Playing companion app (see Companion),
     * which listens here. Kiosk Satellite's Local Media Session only follows *other* apps, so a
     * media session published from inside the plugin (Kiosk's own process) is never shown.
     */
    static final int METADATA_PORT = 5555;

    private final File dir, library, config;
    private final String hwAddr;
    private Process process;
    private OutputStream stdin;
    private float gain = 1f;
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private volatile boolean logStatistics;

    Shairport(File dir, String libraryPath, String hwAddr) {
        this.dir = dir;
        this.library = new File(libraryPath);
        this.config = new File(dir, "shairport-sync.conf");
        this.hwAddr = hwAddr;
    }

    /** Writes the config. Returns true if it changed (shairport-sync then needs a restart). */
    synchronized boolean writeConfig(String name, double offsetSeconds, boolean statistics) throws IOException {
        logStatistics = statistics;
        new File(dir, "coverart").mkdirs();
        String text = configText(name, offsetSeconds, statistics, dir.getPath());
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (config.exists() && java.util.Arrays.equals(Files.readAllBytes(config.toPath()), bytes)) return false;
        Files.write(config.toPath(), bytes);
        return true;
    }

    /** The shairport-sync config file's text (libconfig syntax). */
    static String configText(String name, double offsetSeconds, boolean statistics, String dirPath) {
        File dir = new File(dirPath);
        return "general = {\n"
                + "  name = \"" + escape(name) + "\";\n"
                + "  output_backend = \"aaudio\";\n"
                + "  mdns_backend = \"dns-sd\";\n"
                // No "port" line: 7000 is the default, and shairport-sync 5.6.2 treats a config that
                // sets one as a multi-instance setup and derives the AirPlay ID from the name, so a
                // rename would make senders see a new speaker.
                + String.format(Locale.ROOT, "  audio_backend_latency_offset_in_seconds = %.3f;\n", offsetSeconds)
                + "};\n"
                + "metadata = {\n"
                + "  enabled = \"yes\";\n"
                + "  include_cover_art = \"yes\";\n"
                + "  cover_art_cache_directory = \"" + escape(new File(dir, "coverart").getPath()) + "\";\n"
                + "  pipe_name = \"" + escape(new File(dir, "metadata-pipe").getPath()) + "\";\n"
                + "  socket_address = \"127.0.0.1\";\n"
                + "  socket_port = " + METADATA_PORT + ";\n"
                + "  socket_msglength = 65000;\n"
                + "};\n"
                + "diagnostics = {\n"
                + "  log_verbosity = 0;\n"
                + "  statistics = \"" + (statistics ? "yes" : "no") + "\";\n"
                + "};\n";
    }

    synchronized void start() throws IOException {
        if (isRunning()) return;
        ProcessBuilder pb = new ProcessBuilder("/system/bin/linker", library.getPath(), "-c", config.getPath());
        pb.environment().put("NQPTP_SHM_DIR", dir.getPath());
        pb.environment().put("SPS_MDNS_PORT", Integer.toString(MDNS_PORT));
        pb.environment().put("SPS_HW_ADDR", hwAddr);
        pb.environment().put("HOME", dir.getPath());
        pb.directory(dir);
        pb.redirectErrorStream(true);
        process = pb.start();
        stdin = process.getOutputStream();
        sendGain();
        Process p = process;
        Thread t = new Thread(() -> readLog(p), "airplay2-shairport-log");
        t.setDaemon(true);
        t.start();
        Log.i("shairport-sync started");
    }

    synchronized boolean isRunning() { return process != null && process.isAlive(); }

    synchronized int exitCode() { return process != null && !process.isAlive() ? process.exitValue() : 0; }

    synchronized void stop() {
        if (process == null) return;
        process.destroy(); // SIGTERM: shairport-sync withdraws its mDNS records and exits
        try {
            if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
        }
        process = null;
        stdin = null;
    }

    /** Linear amplitude 0..1 applied on top of the sender's volume. */
    synchronized void setGain(float g) {
        if (Math.abs(g - gain) < 0.0005f) return;
        gain = g;
        sendGain();
    }

    private void sendGain() {
        if (stdin == null) return;
        try {
            stdin.write(String.format(Locale.ROOT, "gain %.4f\n", gain).getBytes(StandardCharsets.US_ASCII));
            stdin.flush();
        } catch (IOException e) {
            Log.d("can't send gain: " + e);
        }
    }

    /** The last lines shairport-sync printed, for the status line when it stops. */
    synchronized String lastLines() {
        return String.join("\n", recent);
    }

    private void readLog(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                synchronized (this) {
                    recent.addLast(t);
                    while (recent.size() > 8) recent.removeFirst();
                }
                boolean stats = t.contains("player.c:4260");
                if (stats ? logStatistics : (t.contains("fatal") || t.contains("error") || t.contains("Playback")
                        || t.contains("AirPlay 2") || t.contains("aaudio: stream error"))) {
                    Log.i("shairport-sync: " + t.replaceFirst("^[0-9.]+\\s+\"[^\"]*\"\\s*", ""));
                } else {
                    Log.d("shairport-sync: " + t);
                }
            }
        } catch (IOException ignored) {
        }
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
