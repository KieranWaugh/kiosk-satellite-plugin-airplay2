package io.github.kieranwaugh.airplay2.nowplaying;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the now-playing service when the device boots. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startForegroundService(new Intent(context, NowPlayingService.class));
        }
    }
}
