package io.github.kieranwaugh.airplay2.nowplaying;

import android.content.Intent;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import java.util.ArrayList;
import java.util.List;

/**
 * An empty media browser over the Now Playing session. Its intent filter is what makes this app
 * visible to Kiosk Satellite on Android 11+, so the plugin inside Kiosk can start NowPlayingService.
 * There is nothing to browse: AirPlay is controlled from the sender.
 */
public class BrowseService extends MediaBrowserService {
    @Override
    public void onCreate() {
        super.onCreate();
        MediaSession s = NowPlayingService.current;
        if (s == null) {
            startForegroundService(new Intent(this, NowPlayingService.class));
            s = NowPlayingService.current;
        }
        if (s != null) setSessionToken(s.getSessionToken());
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        result.sendResult(new ArrayList<>());
    }
}
