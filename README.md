# AirPlay 2 Speaker for Kiosk Satellite

Makes a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) device an AirPlay 2 speaker. An iPhone, iPad or Mac can play to it alone, or group it with HomePods and other AirPlay 2 speakers and have them play in sync.

- **In sync with other AirPlay 2 speakers**, using [shairport-sync](https://github.com/mikebrady/shairport-sync) 5.6.2 and [NQPTP](https://github.com/mikebrady/nqptp) 1.2.9 built for Android.
- **Follows Kiosk Satellite's media volume** and **ducks while the assistant listens or answers**, like Kiosk's own players. The sender's volume still works within it.
- **Shows the track in Now Playing**: title, artist, album, artwork and progress.

Not affiliated with Apple. AirPlay is a trademark of Apple Inc.

## Requirements

| | |
| --- | --- |
| Kiosk Satellite | 2026.11.5 or newer |
| Android | 8.0 (API 26) or newer, on a device that runs 32-bit ARM code (`armeabi-v7a`). Most 64-bit ARM devices do; x86 devices and 64-bit-only devices don't. |
| Timing ports | AirPlay 2 grouping needs UDP ports 319 and 320. Most Android kernels don't let apps open ports below 1024, so on most devices NQPTP needs **root** (`su`, for example Magisk). Allow Kiosk Satellite in your root manager. If the device lets apps open those ports, no root is needed and the plugin uses it automatically. |
| Other receivers | Only one AirPlay 2 receiver can use ports 319, 320 and 7000 on a device. Stop any other one first. |

Tested on an Echo Show 5 (2nd gen) running the [Kiosk Satellite Edition of LineageOS](https://github.com/jxlarrea/lineageos-echo-kiosk-satellite), grouped with a HomePod: steady sync within about ±1.5 ms.

## Installation

### Community Plugins (Recommended)

AirPlay 2 Speaker is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=KieranWaugh/kiosk-satellite-airplay2)

Or search for `AirPlay 2 Speaker` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/KieranWaugh/kiosk-satellite-airplay2` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

Then:

1. If your device needs root for the timing ports, allow **Kiosk Satellite** in your root manager. Magisk 31 shows no prompt: add it in advance under **Superuser**.
2. Set **Speaker name** and enable the plugin.
3. For Now Playing, set Kiosk Satellite's **Media Player > Player** to **Local Media Session** and grant it **Notification access** when asked.

The status line shows `Running as "<name>"` and whether timing runs with or without root.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Speaker name | Kiosk Satellite | The name in the AirPlay menu. Give each device its own. |
| Sync offset (ms) | 0 | Moves playback against the other speakers in a group. Negative plays earlier. Tune it by ear next to a HomePod. |
| Follow the media volume | On | Caps AirPlay at Kiosk Satellite's media volume, as its own players are. |
| Duck to (%) | 5 | AirPlay's level while the assistant listens, thinks or answers. 100 turns ducking off. |
| Log sync statistics | Off | Writes shairport-sync's sync figures to the app log every few seconds. |

## What it installs and where data goes

- **Two helper programs**, shairport-sync and NQPTP, from this plugin's package. They run while the plugin is enabled and stop when it's disabled. NQPTP runs as root only where the timing ports need it.
- **The AirPlay Now Playing companion app** (`io.github.kieranwaugh.airplay2.nowplaying`, about 21 KB). Kiosk Satellite's Local Media Session only follows *other* apps, so the track's media session has to come from a separate app. The plugin installs it silently where Kiosk Satellite is the device owner. Otherwise it uses root, or asks you on screen. You can uninstall it like any app.
- **Network:** the AirPlay speaker listens on your local network (TCP 7000, UDP 319/320 and the ports AirPlay negotiates), and is advertised with Android's own mDNS. Track info passes between the plugin and the companion app over the device's loopback address only. The companion app asks `time.apple.com` for the time (SNTP), so it can show the right progress if the device's clock is off. Nothing else is sent anywhere.
- **Files:** settings, a random device ID and the shairport-sync config, in Kiosk Satellite's private storage.

## Known limits

- **Echo cancellation doesn't cover AirPlay.** Kiosk Satellite's echo canceller only removes sound its own player makes, and the plugin SDK has no way to play through it yet. While AirPlay music plays, the wake word is harder to hear. Lowering the media volume helps.
- **Remote control from Now Playing doesn't work.** In AirPlay 2 sessions the sender gives no way to send play, pause or skip back. Control playback from the sender.
- **One ABI.** Only `armeabi-v7a` fits the plugin's 4 MB package limit.

## How it works

| Part | Runs as | Notes |
| --- | --- | --- |
| shairport-sync | Kiosk Satellite's user | A separate process started through `/system/bin/linker`, so a crash can't take Kiosk down. A custom AAudio backend plays it and reports output delay for sync. The plugin sets its gain from the media volume and the assistant's state. |
| NQPTP | Kiosk Satellite's user, or root | Started without root when ports 319/320 can be opened, otherwise through `su`. Shares its clock with shairport-sync through a file. |
| mDNS | Kiosk Satellite | shairport-sync's DNS-SD records are advertised with Android's NsdManager. |
| Now Playing | Companion app | Decodes the sender's track info and publishes a media session. |

## Building

The release workflow builds everything on `ubuntu-24.04`. Locally (Linux or macOS):

1. Install JDK 17 or newer, an Android SDK with a platform and build-tools, and NDK `27.3.13750724`. Install `autoconf`, `automake`, `libtool`, `pkg-config`, `xxd` and `plistutil`. On Debian or Ubuntu, `plistutil` is in `libplist-utils`.
2. `native/build.sh` downloads every source from its upstream, checks it against a pinned SHA-256 and builds `native/out/armeabi-v7a/`.
3. `companion/build.sh` builds `assets/nowplaying.apk`. Pass `--debug-key` for a local test build, or set `COMPANION_KEYSTORE` and `COMPANION_KEYSTORE_PASSWORD` for the release key.
4. `python3 tools/test.py`, then `python3 tools/build.py`. The ZIP is in `dist/`.

Changes to the upstream sources are in `native/sps/` (the AAudio backend and a small patch) and `native/compat/` (stand-ins for what Android's C library lacks: pthread cancellation, POSIX shared memory, libuuid and dns_sd).

## License

The plugin, companion app and build scripts are [MIT](LICENSE). The SDK interfaces in `sdk/` are Apache-2.0. The packaged native programs are separate works under their own licences:
- shairport-sync: MIT-style; see its `COPYING` and `LICENSES`.
- NQPTP: GPL-2.0.
- OpenSSL: Apache-2.0.
- FFmpeg, libgcrypt, libgpg-error, libplist and libconfig: LGPL-2.1 or later.
- libsodium: ISC.
- popt: MIT.

`native/build.sh` names the exact source version and checksum of each.
