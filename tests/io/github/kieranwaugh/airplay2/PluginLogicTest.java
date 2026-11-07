package io.github.kieranwaugh.airplay2;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Checks the plugin's device-independent logic. Run with java -ea. */
public class PluginLogicTest {
    public static void main(String[] args) {
        gain();
        txt();
        config();
        System.out.println("PluginLogicTest passed");
    }

    private static void near(double a, double b, String what) {
        if (Math.abs(a - b) > 1e-9) throw new AssertionError(what + ": " + a + " != " + b);
    }

    private static void gain() {
        near(AirPlay2Plugin.gain(false, 70, false, 5), 1.0, "not following the media volume");
        near(AirPlay2Plugin.gain(true, 100, false, 5), 1.0, "full media volume");
        near(AirPlay2Plugin.gain(true, 70, false, 5), 0.343, "70% media volume is cubic");
        near(AirPlay2Plugin.gain(true, 70, true, 5), 0.343 * 0.05, "ducked to 5%");
        near(AirPlay2Plugin.gain(true, 150, false, 5), 1.0, "media volume is clamped");
        near(AirPlay2Plugin.gain(true, 50, true, 100), 0.125, "duck 100% means no ducking");
        near(AirPlay2Plugin.gain(false, 50, true, -10), 0.0, "duck level is clamped");
    }

    private static byte[] txt(String... items) {
        int n = 0;
        for (String s : items) n += 1 + s.getBytes(StandardCharsets.UTF_8).length;
        byte[] out = new byte[n];
        int i = 0;
        for (String s : items) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out[i++] = (byte) b.length;
            System.arraycopy(b, 0, out, i, b.length);
            i += b.length;
        }
        return out;
    }

    private static void txt() {
        Map<String, String> m = MdnsBridge.parseTxt(txt("cn=0,1", "features=0x405C4A00,0x1C340", "flag", "pk=abc=def"));
        assert m.size() == 4 : m;
        assert "0,1".equals(m.get("cn")) : m;
        assert "0x405C4A00,0x1C340".equals(m.get("features")) : m;
        assert m.containsKey("flag") && m.get("flag") == null : "bare key";
        assert "abc=def".equals(m.get("pk")) : "only the first '=' splits";
        assert MdnsBridge.parseTxt(new byte[0]).isEmpty();
        assert MdnsBridge.parseTxt(new byte[] {5, 'a'}).containsKey("a") : "truncated item is cut, not thrown";
        byte[] h = MdnsBridge.unhex("00ff7f");
        assert h.length == 3 && h[0] == 0 && h[1] == (byte) 0xff && h[2] == 0x7f;
    }

    private static void config() {
        String c = Shairport.configText("Kitchen \"Display\"", -0.15, false, "/data/x");
        assert c.contains("name = \"Kitchen \\\"Display\\\"\";") : c;
        assert c.contains("audio_backend_latency_offset_in_seconds = -0.150;") : c;
        assert c.contains("output_backend = \"aaudio\";") : c;
        assert c.contains("mdns_backend = \"dns-sd\";") : c;
        assert c.contains("socket_port = " + Shairport.METADATA_PORT + ";") : c;
        assert c.contains("statistics = \"no\";") : c;
        assert c.contains("cover_art_cache_directory = \"/data/x/coverart\";") : c;
        assert !c.matches("(?s).*\\n\\s*port = .*") : "a port line makes shairport-sync derive the AirPlay ID from the name";
        assert Shairport.escape("a\\b\nc").equals("a\\\\b c");
    }
}
