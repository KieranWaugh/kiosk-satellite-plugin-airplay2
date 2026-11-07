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
 * mDNS responder. shairport-sync's dns_sd shim sends one UDP datagram per service to
 * 127.0.0.1:MDNS_PORT, re-sent every 30 s:
 *   "REG <id> <port> <regtype> <name hex> <TXT rdata hex | ->"   or   "DEL <id>"
 * NsdManager can't update a TXT record in place on Android 11, so a change re-registers.
 */
final class MdnsBridge {
    private final NsdManager nsd;
    private final Map<String, Registration> services = new HashMap<>();
    private DatagramSocket socket;
    private Thread thread;
    private volatile boolean running;

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
        running = true;
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), Shairport.MDNS_PORT));
        } catch (Exception e) {
            socket.close();
            throw new SocketException("can't listen on 127.0.0.1:" + Shairport.MDNS_PORT + ": " + e);
        }
        thread = new Thread(this::loop, "airplay2-mdns");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (socket != null) socket.close();
        synchronized (services) {
            for (Registration r : services.values()) unregister(r);
            services.clear();
        }
    }

    private void loop() {
        byte[] buf = new byte[8192];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                handle(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII));
            } catch (Exception e) {
                if (running) Log.w("mDNS bridge", e);
            }
        }
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
            Registration old = services.get(f[1]);
            if (old != null && old.message.equals(msg)) return; // the 30 s repeat: nothing changed
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

    static byte[] unhex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
