package io.github.kieranwaugh.airplay2;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PlayerListener;
import me.jxl.kiosk.plugins.PluginHost;
import me.jxl.kiosk.plugins.PluginPlayer;

/**
 * AirPlay 2 Speaker: a Kiosk Satellite player that runs shairport-sync (the AirPlay 2 receiver)
 * and NQPTP (its PTP timing companion, behind a root-made port redirect: Nqptp, PortRedirect,
 * Root) as helper processes while it is the pick under
 * Media Player > Player. The audio goes to the player's output (AudioBridge), so the echo
 * canceller hears it and Kiosk applies its media volume and the assistant's duck. The track goes
 * to Now Playing through the player's state (NowPlaying). The speaker is advertised with
 * Android's mDNS (MdnsBridge) only while it is the pick, so it only shows up where it can play.
 *
 * Kiosk Satellite's callbacks (configure, onEvent, onActivate, onDeactivate) only note what
 * changed and wake the supervisor thread, which does the slow work (root commands can take
 * seconds), so they never wait for a lock. stop() returns within about 2.5 s.
 */
public class AirPlay2Plugin implements KioskPlugin, PlayerListener {
    static final String PLAYER_KEY = "airplay";

    private PluginHost host;
    private File dir;
    private Root root;
    private Nqptp nqptp;
    private Shairport shairport;
    private MdnsBridge mdns;
    private NowPlaying nowPlaying;
    private Thread supervisor;
    private volatile boolean running;

    /** The player while it is the pick, else null. */
    private volatile PluginPlayer player;
    /** Whether the helpers are up (supervisor thread only). */
    private boolean serving;

    // settings
    private volatile String speakerName = "Kiosk Satellite";
    private volatile double offsetMs;
    private volatile boolean statistics;
    private String publishedName;

    private volatile String lastStatus;

    // Noted by Kiosk Satellite's callbacks, handled by the supervisor.
    private volatile boolean configChanged, shizukuChanged;
    /** Whether to remove a redirect an earlier session left (supervisor thread only). */
    private boolean tidyPending = true;

    /** stop() returns within this, whatever the helpers are doing. */
    static final long STOP_BUDGET_MS = 2500;

    @Override
    public void start(PluginHost host, Map<String, Object> settings) throws Exception {
        this.host = host;
        Log.setHost(host);
        Context context = appContext();
        dir = new File(context.getNoBackupFilesDir(), "airplay2-speaker");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("can't create " + dir);
        readSettings(settings);

        nowPlaying = new NowPlaying(this::showState);
        nowPlaying.start();
        root = Root.forHost(host);
        nqptp = new Nqptp(dir, host.nativeLibraryPath("nqptp"), root);
        nqptp.noteUpgrade(); // before deviceId() below writes device-id
        shairport = new Shairport(dir, host.nativeLibraryPath("shairport"), deviceId(), this::openAudio);
        shairport.writeConfig(speakerName, offsetMs / 1000.0, statistics, nowPlaying.port());
        mdns = new MdnsBridge(context);

        running = true;
        supervisor = new Thread(this::supervise, "airplay2-supervisor");
        supervisor.start();
        publishPlayer();
    }

    @Override
    public void configure(Map<String, Object> settings) throws Exception {
        readSettings(settings);
        publishPlayer();
        configChanged = true; // the supervisor rewrites the config and restarts shairport-sync if it changed
        wake();
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {}

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        // Shizuku connected, disconnected or was granted: the supervisor looks for root again if
        // timing isn't up. Only a flag here: the supervisor may hold the locks for a while.
        if ("shizuku.state".equals(event)) {
            shizukuChanged = true;
            wake();
        }
    }

    @Override
    public void stop() {
        long deadline = System.currentTimeMillis() + STOP_BUDGET_MS;
        running = false;
        player = null;
        // From here on nothing starts a helper, even a supervisor that is midway through a start.
        if (shairport != null) shairport.close();
        if (nqptp != null) nqptp.close();
        wake();
        if (supervisor != null) {
            supervisor.interrupt(); // ends a wait for a root command
            join(supervisor, Math.min(500, deadline - System.currentTimeMillis()));
        }
        // The tidy-up (stop the helpers, withdraw the services, remove the redirect) gets the rest of
        // the budget; if root is slow it finishes in the background.
        Thread tidy = new Thread(this::stopServingQuietly, "airplay2-stop");
        tidy.setDaemon(true);
        tidy.start();
        join(tidy, deadline - System.currentTimeMillis());
        if (nowPlaying != null) nowPlaying.stop();
        Log.setHost(null);
    }

    private static void join(Thread t, long ms) {
        if (ms <= 0) return;
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopServingQuietly() {
        try {
            stopServing();
        } catch (Throwable e) { // plugins run inside Kiosk Satellite's process: nothing may escape a thread
            Log.w("stopping the AirPlay receiver", e);
        }
    }

    // ---- the player ------------------------------------------------------------------------

    private void publishPlayer() {
        String name = speakerName;
        if (name.equals(publishedName)) return;
        host.publishPlayer(PLAYER_KEY, name.length() > 80 ? name.substring(0, 80) : name, this);
        publishedName = name;
        if (player == null) status(idleStatus(), false);
    }

    @Override
    public void onActivate(PluginPlayer player) {
        Log.i("picked as the player; starting the AirPlay receiver");
        this.player = player;
        wake(); // the supervisor starts the helpers; this call must return quickly
    }

    @Override
    public void onDeactivate() {
        Log.i("no longer the player; stopping the AirPlay receiver");
        player = null;
        wake();
    }

    @Override
    public void onCommand(String command, Map<String, Object> arguments) {
        // No actions are offered: an AirPlay 2 sender gives a receiver no remote control.
    }

    private me.jxl.kiosk.plugins.AudioOutput openAudio(int sampleRate, int channels) {
        PluginPlayer p = player;
        if (p == null || !p.isActive()) throw new IllegalStateException("not the player");
        return p.openAudio(sampleRate, channels, PluginPlayer.ENCODING_PCM_16);
    }

    private void showState(Map<String, Object> state) {
        PluginPlayer p = player;
        if (p == null || !p.isActive()) return;
        try {
            p.update(state);
        } catch (RuntimeException e) {
            Log.d("can't update Now Playing: " + e);
        }
    }

    private String idleStatus() {
        return "Not the player. Pick \"AirPlay 2 Speaker for Kiosk Satellite (multiroom and sync): " + speakerName
                + "\" under Settings > Media Player > Player to start the AirPlay receiver.";
    }

    // ---- the helpers -------------------------------------------------------------------------

    private void supervise() {
        try {
            superviseLoop();
        } catch (Throwable e) { // plugins run inside Kiosk Satellite's process: nothing may escape a thread
            Log.w("the AirPlay supervisor stopped", e);
            status("Stopped after an error: " + e, true);
        }
    }

    private void superviseLoop() {
        int quickExits = 0;
        long startedAt = 0;
        long lastCheck = 0;
        while (running) {
            if (shizukuChanged) {
                shizukuChanged = false;
                if (!nqptp.isRunning()) root.forget();
                tidyPending = true;
            }
            if (configChanged) {
                configChanged = false;
                try {
                    if (shairport.writeConfig(speakerName, offsetMs / 1000.0, statistics, nowPlaying.port()) && shairport.isRunning()) {
                        Log.i("settings changed; restarting shairport-sync");
                        shairport.stop(); // started again below with the new config if this is the pick
                    }
                } catch (Exception e) {
                    status("Can't write the shairport-sync config: " + e.getMessage(), true);
                }
            }
            if (player == null) {
                if (serving) {
                    stopServing();
                    status(idleStatus(), false);
                }
                if (tidyPending && running) {
                    tidyPending = false;
                    try {
                        if (nqptp.redirectMayRemain()) nqptp.tidyUp();
                    } catch (InterruptedException e) {
                        continue; // stop()
                    }
                }
                quickExits = 0;
                startedAt = 0;
                lastCheck = 0;
                sleep(5000);
                continue;
            }
            try {
                if (!serving) {
                    mdns.start();
                    serving = true;
                }
                nqptp.start();
                // Every 30 s put the PTP port redirect back if something removed it. Not straight
                // after starting: start() has just added it, and Shizuku takes four commands a second.
                long now = System.currentTimeMillis();
                if (lastCheck == 0) {
                    lastCheck = now;
                } else if (now - lastCheck > 30000) {
                    lastCheck = now;
                    nqptp.maintain();
                }
                if (shairport.isRunning() && shairport.ipv4Only() != nqptp.ipv4Only()) {
                    Log.i("timing changed between IPv4 only and both; restarting shairport-sync");
                    shairport.stop();
                }
                shairport.setIpv4Only(nqptp.ipv4Only());
                if (!shairport.isRunning()) {
                    if (startedAt != 0) {
                        long ran = System.currentTimeMillis() - startedAt;
                        quickExits = ran < 15000 ? quickExits + 1 : 0;
                        Log.i("shairport-sync stopped (exit " + shairport.exitCode() + ") after " + ran / 1000 + " s");
                        if (quickExits >= 3) {
                            status("shairport-sync keeps stopping:\n" + shairport.lastLines(), true);
                            sleep(Math.min(60000, 5000L * quickExits));
                            if (!running || player == null) continue;
                        }
                    }
                    if (!running || player == null) continue;
                    shairport.start(mdns.port(), mdns.token());
                    startedAt = System.currentTimeMillis();
                }
                status("Running as \"" + speakerName + "\" (" + nqptp.describe() + ")", false);
            } catch (Exception e) {
                if (!running) break;
                status(e.getMessage() != null ? e.getMessage() : e.toString(), true);
                sleep(10000);
                continue;
            }
            sleep(2000);
        }
    }

    /** Stops shairport-sync, NQPTP and the mDNS records, in parallel where it helps. */
    private synchronized void stopServing() {
        Thread a = new Thread(() -> {
            try {
                if (shairport != null) shairport.stop();
            } catch (Throwable e) {
                Log.w("stopping shairport-sync", e);
            }
        }, "airplay2-stop-shairport");
        Thread b = new Thread(() -> { // removing the redirect needs root once more
            try {
                if (nqptp != null) nqptp.stop();
            } catch (Throwable e) {
                Log.w("stopping NQPTP", e);
            }
        }, "airplay2-stop-nqptp");
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        try {
            a.join(1500);
        } catch (InterruptedException ignored) {
        }
        if (mdns != null) mdns.stop(); // after shairport-sync, which withdraws its records as it exits
        try {
            b.join(1800);
        } catch (InterruptedException ignored) {
        }
        if (nowPlaying != null) nowPlaying.end();
        serving = false;
    }

    private volatile boolean wakeRequested;

    /** Ends the supervisor's current wait early. */
    private void wake() { wakeRequested = true; }

    private void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (running && !wakeRequested && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return; // stop()
            }
        }
        wakeRequested = false;
    }

    private void readSettings(Map<String, Object> s) {
        if (s == null) return;
        Object v = s.get("speakerName");
        if (v instanceof String && !((String) v).trim().isEmpty()) speakerName = ((String) v).trim();
        v = s.get("latencyOffsetMs");
        if (v instanceof Number) offsetMs = ((Number) v).doubleValue();
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
