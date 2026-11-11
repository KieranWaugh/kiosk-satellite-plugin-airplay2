package io.github.kieranwaugh.airplay2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Short root commands, through whichever way this device offers: Shizuku running as root (Kiosk
 * Satellite's shizuku capability, e.g. started with Rooted debugging; survives image updates) or
 * Magisk's su. Shizuku is tried first because it shows no toast for each command. Nothing here
 * runs for long: the plugin only adds and removes the PTP port redirect and sets NQPTP's priority.
 */
final class Root {
    /** One way to run a root command. */
    interface Shell {
        String name();
        /** Runs the script with sh -c. Throws if this way isn't available at all. */
        Result run(String script, int timeoutMs) throws IOException, InterruptedException;
    }

    static final class Result {
        final int exit;
        final String output;

        Result(int exit, String output) {
            this.exit = exit;
            this.output = output == null ? "" : output.trim();
        }

        boolean ok() { return exit == 0; }
    }

    private final List<Shell> shells;
    private Shell found;
    private String why = "";

    Root(List<Shell> shells) {
        this.shells = new ArrayList<>(shells);
    }

    /** Shizuku first, then su. */
    static Root forHost(PluginHost host) {
        List<Shell> shells = new ArrayList<>();
        shells.add(new ShizukuShell(host));
        shells.add(new SuShell());
        return new Root(shells);
    }

    /** The first way that runs commands as uid 0, remembered; null if none (see why()). */
    synchronized Shell find() throws InterruptedException {
        if (found != null) return found;
        List<String> reasons = new ArrayList<>();
        for (Shell s : shells) {
            try {
                Result r = s.run("id -u", 15000);
                if (r.ok() && r.output.equals("0")) {
                    found = s;
                    Log.i("root commands go through " + s.name());
                    return s;
                }
                reasons.add(s.name() + ": " + (r.ok() ? "runs as uid " + r.output + ", not root" : r.output.isEmpty() ? "exit " + r.exit : r.output));
            } catch (IOException e) {
                reasons.add(s.name() + ": " + e.getMessage());
            }
        }
        why = String.join("; ", reasons);
        return null;
    }

    synchronized String why() { return why; }

    /** Look again next time, e.g. after Shizuku's state changed. */
    synchronized void forget() { found = null; }

    /** Runs a script as root, or throws if there's no way to. */
    Result run(String script, int timeoutMs) throws IOException, InterruptedException {
        Shell s = find();
        if (s == null) throw new IOException("no root: " + why());
        return s.run(script, timeoutMs);
    }

    /**
     * Runs a tidy-up script with the way that worked before, falling back to su; with none found
     * yet, each way in turn. Used from stop() too: Kiosk Satellite 2026.11.16 and newer keep the
     * plugin's Shizuku access open while stop() runs and deliver the result on their own thread,
     * so this can wait for it. Doesn't probe: a device without su fails at once.
     */
    Result runToTidy(String script, int timeoutMs) throws IOException, InterruptedException {
        Shell first;
        synchronized (this) { first = found; }
        IOException failure = null;
        for (Shell s : shells) {
            if (first != null && s != first && !(s instanceof SuShell)) continue;
            try {
                Result r = s.run(script, timeoutMs);
                if (r.ok()) return r;
                failure = new IOException(s.name() + ": " + r.output);
            } catch (IOException e) {
                failure = new IOException(s.name() + ": " + e.getMessage());
            }
        }
        throw failure != null ? failure : new IOException("no root");
    }

    // ---- the two ways ------------------------------------------------------------------------

    /** Kiosk Satellite's Shizuku connection; only counts when its backend is root (uid 0). */
    static final class ShizukuShell implements Shell {
        /** Kiosk Satellite refuses a Shizuku command sent within 250 ms of the previous one. */
        static final long GAP_MS = 300;
        private final PluginHost host;
        private long lastCall;

        ShizukuShell(PluginHost host) { this.host = host; }

        @Override public String name() { return "Shizuku"; }

        @Override
        public synchronized Result run(String script, int timeoutMs) throws IOException, InterruptedException {
            long wait = lastCall + GAP_MS - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastCall = System.currentTimeMillis();
            Map<String, Object> state;
            try {
                state = host.shizukuState();
            } catch (RuntimeException e) {
                throw new IOException("unavailable in this Kiosk Satellite");
            }
            if (state == null || !Boolean.TRUE.equals(state.get("available"))) throw new IOException("not running");
            if (!Boolean.TRUE.equals(state.get("granted"))) throw new IOException("Kiosk Satellite has no access (Settings > Device > Shizuku)");
            Object uid = state.get("uid");
            if (!(uid instanceof Number) || ((Number) uid).intValue() != 0) throw new IOException("running as shell, not root");
            CountDownLatch done = new CountDownLatch(1);
            Result[] result = new Result[1];
            String[] failure = new String[1];
            try {
                host.executeShizuku(new String[] {"/system/bin/sh", "-c", script}, Math.max(100, Math.min(30000, timeoutMs)), (ok, data, error) -> {
                  try {
                    if (ok && data instanceof Map) {
                        Map<?, ?> m = (Map<?, ?>) data;
                        Object code = m.get("exitCode");
                        String out = String.valueOf(m.get("stdout") == null ? "" : m.get("stdout"))
                                + String.valueOf(m.get("stderr") == null ? "" : m.get("stderr"));
                        boolean timedOut = Boolean.TRUE.equals(m.get("timedOut"));
                        result[0] = new Result(timedOut ? -1 : code instanceof Number ? ((Number) code).intValue() : -1, timedOut ? "timed out " + out : out);
                    } else {
                        failure[0] = error == null ? "failed" : error;
                    }
                  } catch (Throwable e) { // this runs on Kiosk Satellite's thread
                    failure[0] = String.valueOf(e);
                  } finally {
                    done.countDown();
                  }
                });
            } catch (RuntimeException e) {
                throw new IOException(String.valueOf(e.getMessage()));
            }
            // Kiosk Satellite enforces the command's timeout itself; allow a little more for the answer.
            if (!done.await(timeoutMs + Math.min(8000L, Math.max(500L, timeoutMs)), TimeUnit.MILLISECONDS)) throw new IOException("no answer");
            if (failure[0] != null) throw new IOException(failure[0]);
            if (result[0] == null) throw new IOException("no result");
            return result[0];
        }
    }

    /** su (Magisk). Kiosk Satellite has to be allowed in Magisk > Superuser. */
    static final class SuShell implements Shell {
        @Override public String name() { return "su"; }

        @Override
        public Result run(String script, int timeoutMs) throws IOException, InterruptedException {
            Process p;
            try {
                p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            } catch (IOException e) {
                throw new IOException("not found");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        synchronized (out) {
                            if (out.size() < 32768) out.write(b, 0, n);
                        }
                    }
                } catch (Throwable ignored) { // nothing may escape a thread in Kiosk Satellite's process
                }
            }, "airplay2-su");
            reader.setDaemon(true);
            reader.start();
            try {
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    return new Result(-1, "timed out (allow Kiosk Satellite in Magisk > Superuser)");
                }
                reader.join(500);
            } catch (InterruptedException e) { // the plugin is stopping
                p.destroyForcibly();
                throw e;
            }
            String text;
            synchronized (out) { text = new String(out.toByteArray(), StandardCharsets.UTF_8); }
            if (p.exitValue() != 0 && text.trim().isEmpty()) text = "refused (allow Kiosk Satellite in Magisk > Superuser)";
            return new Result(p.exitValue(), text);
        }
    }

}
