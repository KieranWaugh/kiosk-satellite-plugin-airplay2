package io.github.kieranwaugh.airplay2;

/**
 * The iptables rules that let NQPTP run as an ordinary app.
 *
 * AirPlay 2 timing (PTP) uses UDP 319 and 320, which apps can't open. These rules, added once
 * with root, move that traffic to 319 + OFFSET and 320 + OFFSET, where NQPTP (started with
 * NQPTP_PORT_OFFSET) listens without root:
 *   - PREROUTING REDIRECT: PTP arriving on 319/320 goes to the high ports;
 *   - POSTROUTING MASQUERADE: PTP NQPTP sends from the high ports leaves from 319/320, so other
 *     speakers (a HomePod leading the group's clock) see an ordinary PTP peer and answer there.
 * The scripts are POSIX sh for Android's mksh. Adding is idempotent (check, then insert), and
 * removing deletes every copy. IPv4 must work; IPv6 NAT is often missing (no ip6tables nat table
 * on 4.9 kernels) and only reported.
 */
final class PortRedirect {
    static final int OFFSET = 10000;
    static final String IPTABLES = "/system/bin/iptables";
    static final String IP6TABLES = "/system/bin/ip6tables";

    private PortRedirect() {}

    /** The rules, as arguments after "-t nat -C|-I|-D". */
    static String[] rules() {
        return new String[] {
            "PREROUTING -p udp --dport 319 -j REDIRECT --to-ports " + (319 + OFFSET),
            "PREROUTING -p udp --dport 320 -j REDIRECT --to-ports " + (320 + OFFSET),
            "POSTROUTING -p udp --sport " + (319 + OFFSET) + " -j MASQUERADE --to-ports 319",
            "POSTROUTING -p udp --sport " + (320 + OFFSET) + " -j MASQUERADE --to-ports 320",
        };
    }

    /** Adds any missing rule. Prints "<tool> ok" or "<tool> failed: ..."; exits 1 if IPv4 failed. */
    static String addScript(String iptables, String ip6tables) {
        StringBuilder sb = new StringBuilder("r=0; ");
        for (String tool : new String[] {iptables, ip6tables}) {
            sb.append("ok=1; err=; ");
            for (String rule : rules()) {
                sb.append(tool).append(" -w 5 -t nat -C ").append(rule).append(" 2>/dev/null || { e=$(")
                  .append(tool).append(" -w 5 -t nat -I ").append(rule).append(" 2>&1) || { ok=0; err=\"$e\"; }; }; ");
            }
            sb.append("if [ $ok = 1 ]; then echo '").append(tool).append(" ok'; else echo \"")
              .append(tool).append(" failed: $err\"; ");
            if (tool.equals(iptables)) sb.append("r=1; ");
            sb.append("fi; ");
        }
        return sb.append("exit $r").toString();
    }

    static String addScript() { return addScript(IPTABLES, IP6TABLES); }

    /** Removes every copy of every rule; always exits 0. */
    static String removeScript(String iptables, String ip6tables) {
        return removeCommands(iptables, ip6tables) + "exit 0";
    }

    static String removeScript() { return removeScript(IPTABLES, IP6TABLES); }

    /** Removes every copy an earlier session left, then adds one of each (as addScript). */
    static String replaceScript(String iptables, String ip6tables) {
        return removeCommands(iptables, ip6tables) + addScript(iptables, ip6tables);
    }

    static String replaceScript() { return replaceScript(IPTABLES, IP6TABLES); }

    private static String removeCommands(String iptables, String ip6tables) {
        StringBuilder sb = new StringBuilder();
        for (String tool : new String[] {iptables, ip6tables}) {
            for (String rule : rules()) {
                sb.append("while ").append(tool).append(" -w 5 -t nat -D ").append(rule).append(" 2>/dev/null; do :; done; ");
            }
        }
        return sb.toString();
    }

    /** Exits 0 if all the IPv4 rules are in place. */
    static String checkScript(String iptables) {
        StringBuilder sb = new StringBuilder();
        for (String rule : rules()) {
            sb.append(iptables).append(" -w 5 -t nat -C ").append(rule).append(" 2>/dev/null || exit 1; ");
        }
        return sb.append("exit 0").toString();
    }

    static String checkScript() { return checkScript(IPTABLES); }

    /** Gives NQPTP (pid) the real-time priority it asks for and can't set as an app. */
    static String priorityScript(int pid) {
        // toybox chrt: -p PID PRIORITY (util-linux puts the priority first)
        return "/system/bin/chrt -f -p " + pid + " 5 2>&1 && echo ok";
    }
}
