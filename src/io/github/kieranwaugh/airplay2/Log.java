package io.github.kieranwaugh.airplay2;

import me.jxl.kiosk.plugins.PluginHost;

/** Logs to logcat (tag AirPlay2Plugin); info and errors also go to Kiosk Satellite's app log. */
final class Log {
    private static final String TAG = "AirPlay2Plugin";
    private static volatile PluginHost host;

    private Log() {}

    static void setHost(PluginHost h) { host = h; }

    static void d(String m) { android.util.Log.d(TAG, m); }

    static void i(String m) {
        android.util.Log.i(TAG, m);
        PluginHost h = host;
        if (h != null) {
            try { h.log(m); } catch (Exception ignored) {}
        }
    }

    static void w(String m, Throwable t) {
        android.util.Log.w(TAG, m, t);
        PluginHost h = host;
        if (h != null) {
            try { h.log(m + (t != null ? ": " + t : "")); } catch (Exception ignored) {}
        }
    }
}
