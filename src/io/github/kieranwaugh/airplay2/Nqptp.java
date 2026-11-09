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
 * NQPTP, the PTP timing companion AirPlay 2 needs. Senders time the group on UDP 319 and 320,
 * which ordinary apps can't open on most Android kernels. start() picks, in order:
 *  - APP: the ports are open to us; NQPTP runs as Kiosk Satellite, no root at all;
 *  - REDIRECT: a short root command (Shizuku as root, or su) adds iptables rules that move 319/320
 *    to 10319/10320 (PortRedirect), and NQPTP runs as Kiosk Satellite on those. Root is used again
 *    only to set NQPTP's real-time priority, check the rules and remove them on stop;
 *  - ROOT: only if the redirect fails (no iptables NAT) and su works: NQPTP itself runs as root.
 * NQPTP shares its clock with shairport-sync through the file dir/nqptp.
 */
final class Nqptp {
    enum Mode { APP, REDIRECT, ROOT }

    private static final String IN_USE = "UDP ports 319/320 are in use by another program, probably another AirPlay 2 receiver on this device (such as a Termux setup). Stop it, then turn this plugin off and on.";
    static final String NO_ROOT = "This device won't let apps open UDP ports 319/320, which AirPlay 2 timing needs, so the plugin needs a moment of root to redirect them. Either start Shizuku as root and give Kiosk Satellite access (Settings > Device > Shizuku), or allow Kiosk Satellite in Magisk > Superuser. Then turn this plugin off and on.";

    private static final String STOP_ROOT_NQPTP = "for p in $(pidof linker); do grep -q libnqptp /proc/$p/cmdline 2>/dev/null"
            + " && [ \"$(stat -c %u /proc/$p)\" = 0 ] && kill $p; done; ";

    private final File dir, library, pidFile, shmFile;
    private final Root root;
    private Process process;
    private Mode mode;
    private Root.Shell redirectedBy;
    private boolean ipv6Redirected;
    private final java.util.ArrayDeque<String> output = new java.util.ArrayDeque<>();

    Nqptp(File dir, String libraryPath, Root root) {
        this.dir = dir;
        this.library = new File(libraryPath);
        this.pidFile = new File(dir, "nqptp.pid");
        this.shmFile = new File(dir, "nqptp");
        this.root = root;
    }

    Mode mode() { return mode; }

    /** How the ports are reached, for the status line. */
    synchronized String describe() {
        if (mode == Mode.APP) return "no root needed";
        if (mode == Mode.REDIRECT) return "PTP ports redirected with " + redirectedBy.name();
        if (mode == Mode.ROOT) return "timing (NQPTP) runs as root";
        return "stopped";
    }

    /** Starts NQPTP, or throws with a message for the plugin's status line. */
    synchronized void start() throws IOException, InterruptedException {
        if (isRunning()) return;
        // For ports below 1024 the kernel checks permission before "in use", so an app gets
        // "refused" even when another NQPTP holds them. Only the "free" case is certain.
        String ports = checkPorts(319, 320);
        if (ports.equals("in use")) throw new IOException(IN_USE);
        deleteStale();
        if (ports.equals("free")) {
            launch(0);
            mode = Mode.APP;
            Log.i("NQPTP started without root");
            return;
        }
        Root.Shell shell = root.find();
        if (shell == null) throw new IOException(NO_ROOT + " (" + root.why() + ")");

        if (!checkPorts(319 + PortRedirect.OFFSET, 320 + PortRedirect.OFFSET).equals("free")) {
            killOwnStale(); // an NQPTP of ours that outlived its plugin session
            Thread.sleep(300);
        }
        // A root NQPTP an earlier version left running would sit on 319/320 for nothing: stop it.
        Root.Result r = root.run(STOP_ROOT_NQPTP + PortRedirect.addScript(), 20000);
        if (r.ok()) {
            ipv6Redirected = r.output.contains(PortRedirect.IP6TABLES + " ok");
            if (!ipv6Redirected) Log.d("no IPv6 PTP redirect, so AirPlay listens on IPv4 only: " + r.output);
            launch(PortRedirect.OFFSET);
            mode = Mode.REDIRECT;
            redirectedBy = shell;
            Log.i("NQPTP started without root, PTP ports redirected with " + shell.name());
            raisePriority();
            return;
        }
        Log.i("can't redirect the PTP ports: " + r.output);
        if (shell instanceof Root.SuShell) {
            startAsRoot();
            return;
        }
        throw new IOException("Couldn't redirect UDP 319/320 with iptables through " + shell.name() + ": " + r.output);
    }

    /**
     * True when PTP reaches NQPTP over IPv4 only (the redirect worked for IPv4, not IPv6). A sender
     * that connects over IPv6 times the session over IPv6 too, and NQPTP would never hear it, so
     * shairport-sync must then listen on IPv4 only.
     */
    synchronized boolean ipv4Only() {
        return mode == Mode.REDIRECT && !ipv6Redirected;
    }

    synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    /** Puts the redirect back if something removed it. Shizuku only: su would toast every time. */
    synchronized void maintain() {
        if (mode != Mode.REDIRECT || !(redirectedBy instanceof Root.ShizukuShell) || !isRunning()) return;
        try {
            if (root.run(PortRedirect.checkScript(), 10000).ok()) return;
            Root.Result r = root.run(PortRedirect.addScript(), 20000);
            Log.i("the PTP port redirect was missing; added again: " + r.output);
        } catch (Exception e) {
            Log.d("can't check the PTP port redirect: " + e);
        }
    }

    /** Stops NQPTP and removes the redirect. Uses root once more, so allow about a second. */
    synchronized void stop() {
        if (mode == Mode.ROOT) {
            int pid = pid();
            if (pid > 0) {
                try {
                    Process k = new ProcessBuilder("su", "-c", "kill " + pid).redirectErrorStream(true).start();
                    k.waitFor(1500, TimeUnit.MILLISECONDS);
                    k.destroy();
                } catch (Exception ignored) {
                }
            }
        }
        stopProcess();
        if (mode == Mode.REDIRECT) {
            try {
                Root.Result r = root.run(PortRedirect.removeScript(), 5000);
                if (!r.ok()) Log.i("removing the PTP port redirect: " + r.output);
            } catch (Exception e) {
                Log.i("can't remove the PTP port redirect: " + e);
            }
        }
        pidFile.delete();
        mode = null;
        redirectedBy = null;
        ipv6Redirected = false;
    }

    // ---- starting ----------------------------------------------------------------------------

    /** Runs NQPTP as Kiosk Satellite. The shell writes its pid, then becomes NQPTP. */
    private void launch(int offset) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                "echo $$ > " + q(pidFile.getPath()) + "; exec /system/bin/linker " + q(library.getPath()));
        pb.environment().put("NQPTP_SHM_DIR", dir.getPath());
        if (offset > 0) pb.environment().put("NQPTP_PORT_OFFSET", Integer.toString(offset));
        pb.redirectErrorStream(true);
        process = pb.start();
        drain(process, "nqptp");
        for (int i = 0; i < 5 && process.isAlive(); i++) Thread.sleep(100);
        if (!process.isAlive()) {
            String out = lastOutput();
            process = null;
            if (out.contains("Address already in use")) throw new IOException(IN_USE);
            throw new IOException("NQPTP stopped straight away: " + (out.isEmpty() ? "no output" : out));
        }
    }

    /** NQPTP asks for SCHED_FIFO, which an app can't have; root can give it. Best effort. */
    private void raisePriority() {
        int pid = -1;
        for (int i = 0; i < 10 && (pid = pid()) <= 0; i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (pid <= 0) return;
        try {
            Root.Result r = root.run(PortRedirect.priorityScript(pid), 5000);
            if (r.ok()) Log.d("NQPTP runs at real-time priority");
            else Log.i("can't give NQPTP real-time priority: " + r.output);
        } catch (Exception e) {
            Log.i("can't give NQPTP real-time priority: " + e);
        }
    }

    /** Last resort: NQPTP itself as root through su (the 0.3/0.4 way). */
    private void startAsRoot() throws IOException, InterruptedException {
        // Root can see every process (apps can't: /proc is hidepid=2), so first stop any root
        // NQPTP this plugin left running, e.g. from before Kiosk restarted.
        String cmd = "for p in $(pidof linker); do grep -q libnqptp /proc/$p/cmdline 2>/dev/null && kill $p; done; "
                + "sleep 0.3; echo $$ > " + q(pidFile.getPath()) + "; NQPTP_SHM_DIR=" + q(dir.getPath())
                + " exec /system/bin/linker " + q(library.getPath());
        process = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
        drain(process, "nqptp (root)");
        // Magisk's su stays alive as long as the root command runs, so a live su after 2 s
        // means NQPTP is up. An early exit means su was refused or NQPTP failed.
        for (int i = 0; i < 20 && process.isAlive(); i++) Thread.sleep(100);
        if (!process.isAlive()) {
            String out = lastOutput();
            process = null;
            pidFile.delete();
            if (out.contains("Address already in use")) throw new IOException(IN_USE);
            throw new IOException("NQPTP stopped straight away: " + (out.isEmpty() ? "no output" : out));
        }
        mode = Mode.ROOT;
        Log.i("NQPTP started as root (no iptables NAT for a redirect)");
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
    static String checkPorts(int a, int b) {
        String result = "free";
        for (int port : new int[] {a, b}) {
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

    /** Kills NQPTPs running as us (apps can see their own processes in /proc). */
    private static void killOwnStale() {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        for (File p : procs) {
            if (!p.getName().matches("\\d+")) continue;
            try {
                String cmdline = new String(Files.readAllBytes(new File(p, "cmdline").toPath()), StandardCharsets.UTF_8);
                if (cmdline.contains("libnqptp")) {
                    Log.i("stopping an earlier NQPTP (pid " + p.getName() + ")");
                    new ProcessBuilder("kill", p.getName()).start().waitFor(1, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** NQPTP's pid, from the file its shell wrote (root's can't be checked in /proc). */
    private int pid() {
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

    private String lastOutput() {
        synchronized (output) { return String.join(" ", output); }
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

    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
