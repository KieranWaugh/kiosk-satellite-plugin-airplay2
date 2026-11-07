package io.github.kieranwaugh.airplay2;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * NQPTP, the PTP timing companion AirPlay 2 needs. It must listen on UDP 319 and 320.
 *
 * Ordinary apps can't open ports below 1024 on most Android kernels, so start() checks:
 *  - both ports open for us: run NQPTP as Kiosk Satellite, no root;
 *  - ports refused: run it as root through su (Magisk), if Kiosk Satellite has been granted root;
 *  - a root NQPTP this plugin left running (Kiosk restarted) is stopped and replaced;
 *  - taken by something else: report it (usually another AirPlay 2 receiver on this device).
 * NQPTP shares its clock with shairport-sync through the file dir/nqptp.
 */
final class Nqptp {
    enum Mode { APP, ROOT }

    private static final String IN_USE = "UDP ports 319/320 are in use by another program, probably another AirPlay 2 receiver on this device (such as a Termux setup). Stop it, then turn this plugin off and on.";

    private final File dir, library, pidFile, shmFile;
    private Process process;
    private Mode mode;
    private final java.util.ArrayDeque<String> output = new java.util.ArrayDeque<>();

    Nqptp(File dir, String libraryPath) {
        this.dir = dir;
        this.library = new File(libraryPath);
        this.pidFile = new File(dir, "nqptp.pid");
        this.shmFile = new File(dir, "nqptp");
    }

    Mode mode() { return mode; }

    /** Starts NQPTP, or throws with a message for the plugin's status line. */
    synchronized void start() throws IOException, InterruptedException {
        if (isRunning()) return;
        // Note: for ports below 1024 the kernel checks permission before "in use", so an app gets
        // "refused" even when another NQPTP holds them. Only the "free" case is certain.
        String ports = checkPorts();
        if (ports.equals("in use"))
            throw new IOException(IN_USE);
        deleteStale();
        if (ports.equals("free")) {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/linker", library.getPath());
            pb.environment().put("NQPTP_SHM_DIR", dir.getPath());
            pb.redirectErrorStream(true);
            process = pb.start();
            drain(process, "nqptp");
            mode = Mode.APP;
            Log.i("NQPTP started without root");
            return;
        }
        // Ports refused: needs root. Root can see every process (apps can't: /proc is hidepid=2),
        // so first stop any NQPTP this plugin left running, e.g. from before Kiosk restarted.
        String cmd = "for p in $(pidof linker); do grep -q libnqptp /proc/$p/cmdline 2>/dev/null && kill $p; done; "
                + "sleep 0.3; echo $$ > " + q(pidFile.getPath()) + "; NQPTP_SHM_DIR=" + q(dir.getPath())
                + " exec /system/bin/linker " + q(library.getPath());
        ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
        pb.redirectErrorStream(true);
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("This device won't let apps open UDP ports 319/320, and root (su) isn't available. AirPlay 2 timing needs one or the other.");
        }
        drain(process, "nqptp (root)");
        // Magisk's su stays alive as long as the root command runs, so a live su after 2 s
        // means NQPTP is up. An early exit means su was refused or NQPTP failed.
        for (int i = 0; i < 20 && process.isAlive(); i++) Thread.sleep(100);
        if (!process.isAlive()) {
            String out;
            synchronized (output) { out = String.join(" ", output); }
            boolean wroteFile = rootPid() > 0;
            pidFile.delete();
            process = null;
            if (!wroteFile) {
                throw new IOException("This device won't let apps open UDP ports 319/320, so NQPTP needs root, but su was refused. In Magisk > Superuser, allow Kiosk Satellite, then turn this plugin off and on." + (out.isEmpty() ? "" : " (" + out + ")"));
            }
            if (out.contains("Address already in use")) throw new IOException(IN_USE);
            throw new IOException("NQPTP stopped straight away: " + (out.isEmpty() ? "no output" : out));
        }
        mode = Mode.ROOT;
        Log.i("NQPTP started as root");
    }

    synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    /** Stops NQPTP. For root mode this runs su once more, so allow about a second. */
    synchronized void stop() {
        if (mode == Mode.ROOT) {
            int pid = rootPid();
            if (pid > 0) {
                try {
                    Process k = new ProcessBuilder("su", "-c", "kill " + pid).redirectErrorStream(true).start();
                    k.waitFor(1500, TimeUnit.MILLISECONDS);
                    k.destroy();
                } catch (Exception ignored) {
                }
            }
            pidFile.delete();
        }
        stopProcess();
        mode = null;
    }

    private void stopProcess() {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(800, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException ignored) {
            process.destroyForcibly();
        }
        process = null;
    }

    /** "free", "refused" or "in use". */
    private static String checkPorts() {
        String result = "free";
        for (int port : new int[] {319, 320}) {
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.bind(new InetSocketAddress(port));
            } catch (BindException e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("EADDRINUSE") || m.contains("in use")) return "in use";
                result = "refused";
            } catch (IOException e) {
                result = "refused";
            }
        }
        return result;
    }

    /** PID from the file root NQPTP wrote when it started (it can't be checked in /proc). */
    private int rootPid() {
        try {
            return Integer.parseInt(new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.US_ASCII).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private void deleteStale() {
        pidFile.delete();
        shmFile.delete(); // may be root's from an earlier run; the directory is ours, so we can
    }

    private void drain(Process p, String tag) {
        synchronized (output) { output.clear(); }
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.d(tag + ": " + line);
                    synchronized (output) {
                        output.addLast(line.trim());
                        while (output.size() > 4) output.removeFirst();
                    }
                }
            } catch (IOException ignored) {
            }
        }, "airplay2-" + tag);
        t.setDaemon(true);
        t.start();
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
