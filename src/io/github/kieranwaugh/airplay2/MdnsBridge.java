package io.github.kieranwaugh.airplay2;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Advertises shairport-sync's AirPlay services with Android's NsdManager, so the device keeps one
 * mDNS responder. shairport-sync's dns_sd shim (native/compat/dns_sd) sends one UDP datagram per
 * service to 127.0.0.1:port(), re-sent every 30 s:
 *   "<token> REG <id> <port> <regtype> <name hex> <TXT rdata hex | ->"   or   "<token> DEL <id>"
 * Any app on the device can send to a loopback port, so each start listens on a new random port
 * and makes a new random token, which only shairport-sync gets (in its environment, which other
 * apps can't read). Datagrams without the token are dropped, and only the two AirPlay service
 * types are advertised. NsdManager can't update a TXT record in place on Android 11, so a change
 * re-registers.
 */
final class MdnsBridge {
    /** shairport-sync registers two services; anything more is refused. */
    static final int MAX_SERVICES = 4;
    static final java.util.Set<String> SERVICE_TYPES = new java.util.HashSet<>(java.util.Arrays.asList("_airplay._tcp", "_raop._tcp"));

    private final NsdManager nsd;
    private final Map<String, Registration> services = new HashMap<>();
    private DatagramSocket socket;
    private Thread thread;
    private volatile boolean running;
    private volatile String token = "";

    private static final class Registration {
        final String message;
        final NsdManager.RegistrationListener listener;
        Registration(String message, NsdManager.RegistrationListener listener) {
            this.message = message;
            this.listener = listener;
        }
    }

    MdnsBridge(Context context) {
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    void start() throws SocketException {
        byte[] t = new byte[16];
        new java.security.SecureRandom().nextBytes(t);
        token = MdnsBridge.hex(t);
        running = true;
        socket = new DatagramSocket(null);
        try {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        } catch (Exception e) {
            socket.close();
            throw new SocketException("can't listen on 127.0.0.1: " + e);
        }
        DatagramSocket own = socket;
        String ownToken = token;
        thread = new Thread(() -> loop(own, ownToken), "airplay2-mdns");
        thread.setDaemon(true);
        thread.start();
    }

    /** The port shairport-sync's shim sends to (SPS_MDNS_PORT), from start() on. */
    int port() { return socket.getLocalPort(); }

    /** The token the shim must put first in every datagram (SPS_MDNS_TOKEN), from start() on. */
    String token() { return token; }

    void stop() {
        running = false;
        if (socket != null) socket.close();
        synchronized (services) {
            for (Registration r : services.values()) unregister(r);
            services.clear();
        }
    }

    /** Receives on its own socket, so a thread from before a stop and start ends with its socket. */
    private void loop(DatagramSocket socket, String token) {
        // Plugins run inside Kiosk Satellite's process: nothing thrown here may escape the thread.
        try {
            byte[] buf = new byte[8192];
            while (running && !socket.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                    String body = authenticated(msg, token);
                    if (body != null) handle(body);
                } catch (Throwable e) {
                    if (running && !socket.isClosed()) Log.w("mDNS bridge", e);
                }
            }
        } catch (Throwable e) {
            Log.w("mDNS bridge stopped", e);
        }
    }

    /** The message after "<token> ", or null if it doesn't start with the token. */
    static String authenticated(String msg, String token) {
        int sp = msg.indexOf(' ');
        if (token.isEmpty() || sp <= 0) return null;
        byte[] got = msg.substring(0, sp).getBytes(StandardCharsets.US_ASCII);
        if (!java.security.MessageDigest.isEqual(got, token.getBytes(StandardCharsets.US_ASCII))) return null;
        return msg.substring(sp + 1);
    }

    private void handle(String msg) {
        String[] f = msg.split(" ");
        synchronized (services) {
            if (f.length == 2 && f[0].equals("DEL")) {
                Registration r = services.remove(f[1]);
                if (r != null) unregister(r);
                return;
            }
            if (f.length != 6 || !f[0].equals("REG")) return;
            if (!SERVICE_TYPES.contains(f[3])) {
                Log.d("not advertising service type " + f[3]);
                return;
            }
            Registration old = services.get(f[1]);
            if (old != null && old.message.equals(msg)) return; // the 30 s repeat: nothing changed
            if (old == null && services.size() >= MAX_SERVICES) {
                Log.d("not advertising more than " + MAX_SERVICES + " services");
                return;
            }
            if (old != null) unregister(old);

            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(new String(unhex(f[4]), StandardCharsets.UTF_8));
            info.setServiceType(f[3]);
            info.setPort(Integer.parseInt(f[2]));
            if (!f[5].equals("-")) {
                for (Map.Entry<String, String> e : parseTxt(unhex(f[5])).entrySet()) {
                    try {
                        info.setAttribute(e.getKey(), e.getValue());
                    } catch (IllegalArgumentException x) {
                        Log.d("skipped TXT item " + e.getKey() + ": " + x.getMessage());
                    }
                }
            }
            NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo s) {
                    Log.d("advertised " + s.getServiceName() + " " + info.getServiceType());
                }
                @Override public void onRegistrationFailed(NsdServiceInfo s, int error) {
                    Log.w("can't advertise " + info.getServiceName() + " " + info.getServiceType() + " (error " + error + ")", null);
                }
                @Override public void onServiceUnregistered(NsdServiceInfo s) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int error) {}
            };
            services.put(f[1], new Registration(msg, l));
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
        }
    }

    private void unregister(Registration r) {
        try {
            nsd.unregisterService(r.listener);
        } catch (IllegalArgumentException ignored) {
            // never registered
        }
    }

    /** DNS-SD TXT rdata (length-prefixed "key=value" strings) as an ordered map; null for a bare key. */
    static Map<String, String> parseTxt(byte[] txt) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < txt.length; ) {
            int n = txt[i] & 0xff;
            String item = new String(txt, i + 1, Math.min(n, txt.length - i - 1), StandardCharsets.UTF_8);
            i += 1 + n;
            int eq = item.indexOf('=');
            if (eq > 0) out.put(item.substring(0, eq), item.substring(eq + 1));
            else if (!item.isEmpty()) out.put(item, null);
        }
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static byte[] unhex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
