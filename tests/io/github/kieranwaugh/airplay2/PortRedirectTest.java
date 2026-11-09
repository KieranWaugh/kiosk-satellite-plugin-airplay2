package io.github.kieranwaugh.airplay2;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs PortRedirect's scripts with sh against a fake iptables that keeps its rules in a file, and
 * checks how Root picks a way to run root commands. Run with java -ea.
 */
public class PortRedirectTest {
    static File work;

    public static void main(String[] args) throws Exception {
        work = Files.createTempDirectory("portredirect").toFile();
        try {
            scripts();
            rootChoice();
        } finally {
            for (File f : work.listFiles()) f.delete();
            work.delete();
        }
        System.out.println("PortRedirectTest passed");
    }

    /** A fake iptables: "-w 5 -t nat -C|-I|-D rule..." against rules.<name>; or one that always fails. */
    private static String fake(String name, boolean works) throws Exception {
        File tool = new File(work, name);
        File rules = new File(work, "rules." + name);
        String body = works
                ? "#!/bin/sh\n[ \"$1 $2 $3 $4\" = '-w 5 -t nat' ] || { echo bad args >&2; exit 2; }\nop=$5; shift 5; rule=\"$*\"\n"
                  + "touch '" + rules + "'\n"
                  + "case $op in\n"
                  + " -C) grep -qxF \"$rule\" '" + rules + "' ;;\n"
                  + " -I) echo \"$rule\" >> '" + rules + "' ;;\n"
                  + " -D) grep -qxF \"$rule\" '" + rules + "' || exit 1; awk -v r=\"$rule\" 'n==0 && $0==r {n=1; next} {print}' '" + rules + "' > '" + rules + ".t' && mv '" + rules + ".t' '" + rules + "' ;;\n"
                  + " *) exit 2 ;;\nesac\n"
                : "#!/bin/sh\necho \"can't initialize " + name + " table 'nat': Table does not exist\" >&2\nexit 3\n";
        Files.write(tool.toPath(), body.getBytes(StandardCharsets.UTF_8));
        tool.setExecutable(true);
        return tool.getPath();
    }

    private static List<String> rules(String name) throws Exception {
        File f = new File(work, "rules." + name);
        return f.exists() ? Files.readAllLines(f.toPath()) : new ArrayList<>();
    }

    private static Root.Result sh(String script) throws Exception {
        Process p = new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        return new Root.Result(p.waitFor(), new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    private static void scripts() throws Exception {
        String v4 = fake("ipt", true), v6 = fake("ip6t-missing", false);

        Root.Result r = sh(PortRedirect.addScript(v4, v6));
        assert r.ok() : "IPv6 NAT missing must not fail the redirect: " + r.output;
        assert r.output.contains(v4 + " ok") && r.output.contains(v6 + " failed") && r.output.contains("Table does not exist") : r.output;
        assert rules("ipt").equals(Arrays.asList(PortRedirect.rules())) : rules("ipt");
        assert rules("ipt").get(0).equals("PREROUTING -p udp --dport 319 -j REDIRECT --to-ports 10319");
        assert rules("ipt").get(3).equals("POSTROUTING -p udp --sport 10320 -j MASQUERADE --to-ports 320");

        r = sh(PortRedirect.addScript(v4, v6));
        assert r.ok() && rules("ipt").size() == 4 : "adding again must not duplicate: " + rules("ipt");
        assert sh(PortRedirect.checkScript(v4)).ok();

        // Something removed one rule: the check notices, adding puts back only that one.
        List<String> three = new ArrayList<>(rules("ipt"));
        three.remove(1);
        Files.write(new File(work, "rules.ipt").toPath(), three);
        assert !sh(PortRedirect.checkScript(v4)).ok() : "a missing rule must be noticed";
        assert sh(PortRedirect.addScript(v4, v6)).ok() && rules("ipt").size() == 4 : rules("ipt");

        // Two copies (say, two plugin sessions overlapped): removing clears both.
        assert sh(PortRedirect.addScript(v4, v6)).ok();
        List<String> doubled = new ArrayList<>(rules("ipt"));
        doubled.addAll(rules("ipt"));
        Files.write(new File(work, "rules.ipt").toPath(), doubled);
        r = sh(PortRedirect.removeScript(v4, v6));
        assert r.ok() && rules("ipt").isEmpty() : rules("ipt");
        assert sh(PortRedirect.removeScript(v4, v6)).ok() : "removing nothing is fine";

        // No IPv4 NAT at all: the redirect fails, so the plugin can fall back.
        String broken = fake("ipt-missing", false);
        r = sh(PortRedirect.addScript(broken, v6));
        assert r.exit == 1 && r.output.contains(broken + " failed") : r.output;

        assert PortRedirect.priorityScript(1234).contains("chrt -f -p 1234 5");
    }

    /** A pretend way to run root commands. */
    static final class FakeShell implements Root.Shell {
        final String name, uid;
        final boolean available;
        int calls;

        FakeShell(String name, boolean available, String uid) {
            this.name = name;
            this.available = available;
            this.uid = uid;
        }

        @Override public String name() { return name; }

        @Override
        public Root.Result run(String script, int timeoutMs) throws java.io.IOException {
            calls++;
            if (!available) throw new java.io.IOException("not running");
            return new Root.Result(0, script.equals("id -u") ? uid + "\n" : "did " + script);
        }
    }

    private static void rootChoice() throws Exception {
        FakeShell shizuku = new FakeShell("Shizuku", true, "0"), su = new FakeShell("su", true, "0");
        Root root = new Root(Arrays.asList(shizuku, su));
        assert root.find() == shizuku : "Shizuku as root comes first";
        assert root.run("x", 1000).output.equals("did x");
        assert su.calls == 0 && shizuku.calls == 2 : "found once, then reused";

        FakeShell shellUid = new FakeShell("Shizuku", true, "2000");
        su = new FakeShell("su", true, "0");
        root = new Root(Arrays.asList(shellUid, su));
        assert root.find() == su : "Shizuku as shell (uid 2000) isn't root";

        root = new Root(Arrays.asList(new FakeShell("Shizuku", false, "0"), new FakeShell("su", false, "0")));
        assert root.find() == null;
        assert root.why().contains("Shizuku: not running") && root.why().contains("su: not running") : root.why();
        try {
            root.run("x", 1000);
            throw new AssertionError("ran without root");
        } catch (java.io.IOException expected) {
            assert expected.getMessage().startsWith("no root") : expected;
        }

        // forget(): Shizuku came up later.
        FakeShell late = new FakeShell("Shizuku", false, "0");
        su = new FakeShell("su", false, "0");
        Root later = new Root(Arrays.asList(late, su));
        assert later.find() == null;
        FakeShell nowUp = new FakeShell("Shizuku", true, "0");
        later = new Root(Arrays.asList(nowUp, su));
        later.find();
        later.forget();
        assert later.find() == nowUp;
    }
}
