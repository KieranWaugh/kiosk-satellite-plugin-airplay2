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
 * The plugin talks to it through the config file it writes, its stdout (the audio, read by
 * AudioBridge), its stdin (the output's timestamps, from AudioBridge), its stderr (the log) and two
 * local UDP ports it sends to (mDNS records for MdnsBridge, track metadata for NowPlaying).
 */
final class Shairport {
    private final File dir, library, config;
    private final String hwAddr;
    private final AudioBridge.Opener opener;
    private volatile Process process;
    private OutputStream stdin;
    private AudioBridge bridge;
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private volatile boolean logStatistics;
    /** Set by close(): this instance never starts shairport-sync again. */
    private volatile boolean closed;

    Shairport(File dir, String libraryPath, String hwAddr, AudioBridge.Opener opener) {
        this.dir = dir;
        this.library = new File(libraryPath);
        this.config = new File(dir, "shairport-sync.conf");
        this.hwAddr = hwAddr;
        this.opener = opener;
    }

    /** Writes the config. Returns true if it changed (shairport-sync then needs a restart). */
    synchronized boolean writeConfig(String name, double offsetSeconds, boolean statistics, int metadataPort) throws IOException {
        logStatistics = statistics;
        new File(dir, "coverart").mkdirs();
        String text = configText(name, offsetSeconds, statistics, dir.getPath(), metadataPort);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (config.exists() && java.util.Arrays.equals(Files.readAllBytes(config.toPath()), bytes)) return false;
        Files.write(config.toPath(), bytes);
        return true;
    }

    /** The shairport-sync config file's text (libconfig syntax). */
    static String configText(String name, double offsetSeconds, boolean statistics, String dirPath, int metadataPort) {
        File dir = new File(dirPath);
        return "general = {\n"
                + "  name = \"" + escape(name) + "\";\n"
                + "  output_backend = \"plugin\";\n"
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
                + "  socket_port = " + metadataPort + ";\n"
                + "  socket_msglength = 65000;\n"
                + "};\n"
                + "diagnostics = {\n"
                + "  log_verbosity = 0;\n"
                + "  statistics = \"" + (statistics ? "yes" : "no") + "\";\n"
                + "};\n";
    }

    private volatile boolean ipv4Only;

    /** Listen on IPv4 only from the next start (see Nqptp.ipv4Only). */
    void setIpv4Only(boolean v) { ipv4Only = v; }

    boolean ipv4Only() { return ipv4Only; }

    /** Starts shairport-sync, advertising through the MdnsBridge listening on mdnsPort with mdnsToken. */
    synchronized void start(int mdnsPort, String mdnsToken) throws IOException {
        if (closed) throw new IOException("stopping");
        if (isRunning()) return;
        ProcessBuilder pb = new ProcessBuilder("/system/bin/linker", library.getPath(), "-c", config.getPath());
        pb.environment().put("NQPTP_SHM_DIR", dir.getPath());
        if (ipv4Only) pb.environment().put("SPS_IPV4_ONLY", "1");
        pb.environment().put("SPS_MDNS_PORT", Integer.toString(mdnsPort));
        pb.environment().put("SPS_MDNS_TOKEN", mdnsToken);
        pb.environment().put("SPS_HW_ADDR", hwAddr);
        pb.environment().put("HOME", dir.getPath());
        pb.directory(dir);
        process = pb.start();
        stdin = process.getOutputStream();
        Process p = process;
        bridge = new AudioBridge(p.getInputStream(), opener, this::send);
        bridge.start();
        Thread t = new Thread(() -> readLog(p), "airplay2-shairport-log");
        t.setDaemon(true);
        t.start();
        Log.i("shairport-sync started");
    }

    synchronized boolean isRunning() { return process != null && process.isAlive(); }

    synchronized int exitCode() { return process != null && !process.isAlive() ? process.exitValue() : 0; }

    /**
     * For the plugin's stop(): no start() from now on, and kill what's running without waiting for
     * the lock (a start() may be holding it). stop() still has to tidy up.
     */
    void close() {
        closed = true;
        Process p = process;
        if (p != null) p.destroy();
    }

    synchronized void stop() {
        if (process == null) return;
        process.destroy(); // SIGTERM: shairport-sync withdraws its mDNS records and exits
        try {
            if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
        }
        if (bridge != null) bridge.stop();
        bridge = null;
        process = null;
        stdin = null;
    }

    /** A line for the helper's stdin (AudioBridge's timestamps). */
    private synchronized void send(String line) {
        if (stdin == null) return;
        try {
            stdin.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            stdin.flush();
        } catch (IOException e) {
            Log.d("can't write to shairport-sync: " + e);
        }
    }

    /** The last lines shairport-sync printed, for the status line when it stops. */
    synchronized String lastLines() {
        return String.join("\n", recent);
    }

    private void readLog(Process p) {
        try {
            readLogLines(p);
        } catch (Throwable e) { // plugins run inside Kiosk Satellite's process: nothing may escape the thread
            Log.w("shairport-sync log", e);
        }
    }

    private void readLogLines(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
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
                        || t.contains("AirPlay 2") || t.contains("plugin: can't"))) {
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
