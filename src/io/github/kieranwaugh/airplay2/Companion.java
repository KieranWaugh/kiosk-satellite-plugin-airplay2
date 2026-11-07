package io.github.kieranwaugh.airplay2;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The AirPlay Now Playing companion app (io.github.kieranwaugh.airplay2.nowplaying), bundled as
 * assets/nowplaying.apk. Kiosk Satellite's Local Media Session only follows *other* apps, so the
 * track's media session has to come from a separate package; this keeps the plugin a single ZIP.
 *
 * ensure() installs the bundled copy when the app is missing or older, then starts its service.
 * Kiosk Satellite is usually the device owner, which lets it install silently. Otherwise it falls
 * back to root (pm install), and last to Android's install confirmation on screen.
 */
final class Companion {
    static final String PACKAGE = "io.github.kieranwaugh.airplay2.nowplaying";
    private static final String SERVICE = PACKAGE + ".NowPlayingService";
    private static final String ACTION = "io.github.kieranwaugh.airplay2.COMPANION_INSTALL_STATUS";
    private static final int FLAG_MUTABLE = 0x02000000; // PendingIntent.FLAG_MUTABLE (API 31)
    private static final int RECEIVER_NOT_EXPORTED = 4; // Context.RECEIVER_NOT_EXPORTED (API 33)

    private final Context context;
    private final String apkPath;

    Companion(Context context, String apkPath) {
        this.context = context;
        this.apkPath = apkPath;
    }

    /** Returns a line for the status, or throws with the reason Now Playing won't work. */
    String ensure() throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo bundled = pm.getPackageArchiveInfo(apkPath, 0);
        if (bundled == null) throw new IllegalStateException("the bundled Now Playing app can't be read");
        long want = versionCode(bundled), have = -1;
        try {
            have = versionCode(pm.getPackageInfo(PACKAGE, 0));
        } catch (PackageManager.NameNotFoundException notInstalled) {
            // install it
        }
        String result = "Now Playing app " + bundled.versionName + " is installed";
        if (have < want) {
            Log.i(have < 0 ? "installing the Now Playing app" : "updating the Now Playing app");
            String how = installWithPackageInstaller();
            if (how == null) how = installWithRoot();
            if (how == null) throw new IllegalStateException("couldn't install the Now Playing app, so Now Playing won't show AirPlay tracks");
            result = "Installed the Now Playing app " + bundled.versionName + " (" + how + ")";
            Log.i(result);
        }
        // Right after an install Android may not know the new service yet ("not found"): retry.
        for (int i = 0; i < 20; i++) {
            if (startService()) return result;
            Thread.sleep(500);
        }
        throw new IllegalStateException("the Now Playing app is installed but its service won't start");
    }

    /** Starts the app's service; harmless if it is already running. False if Android can't find it. */
    boolean startService() {
        try {
            return context.startForegroundService(new Intent().setClassName(PACKAGE, SERVICE)) != null;
        } catch (Exception e) {
            Log.d("can't start the Now Playing app: " + e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    /** Silent as device owner; otherwise Android may ask on screen. Null if it didn't install. */
    private String installWithPackageInstaller() {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        final int[] status = {PackageInstaller.STATUS_FAILURE};
        final String[] message = {null};
        final boolean[] asked = {false};
        final CountDownLatch done = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int s = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (s == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    asked[0] = true;
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        Log.i("asking on screen to install the Now Playing app");
                        c.startActivity(confirm);
                    }
                    return; // the final status follows
                }
                status[0] = s;
                message[0] = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                done.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, filter);
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(PACKAGE);
            int id = installer.createSession(params);
            session = installer.openSession(id);
            File apk = new File(apkPath);
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(ACTION).setPackage(context.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, result, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_MUTABLE);
            session.commit(pi.getIntentSender());
            session.close();
            session = null;
            if (!done.await(120, TimeUnit.SECONDS)) {
                Log.i("the Now Playing app install didn't finish in 2 minutes");
                return null;
            }
            if (status[0] == PackageInstaller.STATUS_SUCCESS) return asked[0] ? "confirmed on screen" : "silently, as device owner";
            Log.i("PackageInstaller couldn't install the Now Playing app: " + message[0]);
            return null;
        } catch (Exception e) {
            Log.w("PackageInstaller couldn't install the Now Playing app", e);
            if (session != null) session.abandon();
            return null;
        } finally {
            try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
    }

    /** pm install as root, for devices where Kiosk Satellite isn't the device owner. */
    private String installWithRoot() {
        String tmp = "/data/local/tmp/airplay2-nowplaying.apk";
        String cmd = "cp '" + apkPath.replace("'", "'\\''") + "' " + tmp + " && chmod 644 " + tmp
                + " && pm install -r " + tmp + "; r=$?; rm -f " + tmp + "; exit $r";
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? "with root" : null;
        } catch (Exception e) {
            return null;
        }
    }
}
