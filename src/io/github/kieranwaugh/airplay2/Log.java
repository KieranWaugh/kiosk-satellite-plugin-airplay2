package io.github.kieranwaugh.airplay2;

import me.jxl.kiosk.plugins.PluginHost;

/** Logs to logcat (tag AirPlay2Plugin); info and errors also go to Kiosk Satellite's app log. */
final class Log {
    private static final String TAG = "AirPlay2Plugin";
    private static volatile PluginHost host;
    /** False off the device, where android.util.Log is only a stub (the tests). */
    private static volatile boolean logcat = true;

    private Log() {}

    static void setHost(PluginHost h) { host = h; }

    static void d(String m) { logcat('d', m, null); }

    static void i(String m) {
        logcat('i', m, null);
        PluginHost h = host;
        if (h != null) {
            try { h.log(m); } catch (Exception ignored) {}
        }
    }

    static void w(String m, Throwable t) {
        logcat('w', m, t);
        PluginHost h = host;
        if (h != null) {
            try { h.log(m + (t != null ? ": " + t : "")); } catch (Exception ignored) {}
        }
    }

    private static void logcat(char level, String m, Throwable t) {
        if (logcat) {
            try {
                if (level == 'd') android.util.Log.d(TAG, m);
                else if (level == 'i') android.util.Log.i(TAG, m);
                else android.util.Log.w(TAG, m, t);
                return;
            } catch (RuntimeException stub) {
                logcat = false;
            }
        }
        System.err.println(TAG + " " + level + ": " + m + (t != null ? ": " + t : ""));
    }
}
